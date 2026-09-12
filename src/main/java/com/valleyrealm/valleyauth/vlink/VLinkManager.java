package com.valleyrealm.valleyauth.vlink;

import com.valleyrealm.valleyauth.ValleyAuth;
import com.valleyrealm.valleyauth.config.ConfigManager;
import com.valleyrealm.valleyauth.identity.Identity;
import com.valleyrealm.valleyauth.identity.IdentityType;
import com.valleyrealm.valleyauth.migration.MigrationType;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


/**
 * VLink - Valley Auth's secure identity-linking utility.
 * 
 * VLink is the trusted primitive for:
 * - Web migration session <-> Minecraft player association
 * - Offline identity <-> Verified migration destination
 * - Optional addon-controlled identity relationships
 * 
 * Security requirements:
 * - Short-lived codes
 * - Single-use authorization
 * - Identity verification
 * - Replay protection
 * - Session expiration
 * - UUID association
 */
public class VLinkManager {

    private final ValleyAuth plugin;
    private final ConfigManager config;
    
    // Active VLink sessions (sessionId -> session)
    private final Map<String, VLinkSession> sessions = new ConcurrentHashMap<>();
    
    // Code-to-session mapping (code -> sessionId)
    private final Map<String, String> codeToSession = new ConcurrentHashMap<>();
    
    // Used codes (prevent reuse)
    private final Map<String, Long> usedCodes = new ConcurrentHashMap<>();
    
    private final SecureRandom random = new SecureRandom();

    public VLinkManager(ValleyAuth plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        
        startCleanupTask();
    }

    /**
     * Create a new VLink session for a migration.
     * 
     * @param sourceIdentity The offline identity being migrated
     * @param destinationType The target identity type
     * @param pluginId The addon/plugin requesting the link (for certificate validation)
     * @return The created session with its code
     */
    public VLinkResult createSession(Identity sourceIdentity, IdentityType destinationType, String pluginId) {
        // Validate certificate if plugin is requesting
        if (pluginId != null && !plugin.getCertificateValidator().isValid(pluginId, "VLINK")) {
            return VLinkResult.denied("Invalid or missing certificate for VLink operation.");
        }
        
        // Validate migration is allowed
        if (!plugin.getIdentityManager().isMigrationAllowed(sourceIdentity.getType(), destinationType)) {
            return VLinkResult.denied("Migration from " + sourceIdentity.getType() + " to " + destinationType + " is not allowed.");
        }
        
        // Generate session
        String sessionId = generateSessionId();
        String code = generateCode();
        
        VLinkSession session = new VLinkSession(
            sessionId,
            code,
            sourceIdentity,
            destinationType,
            pluginId,
            config.getVlinkExpiryMinutes()
        );
        
        sessions.put(sessionId, session);
        codeToSession.put(code, sessionId);
        
        plugin.getLogger().info("[VLink] Session created: " + sessionId + " with code: " + code);
        
        return VLinkResult.success(session);
    }

    /**
     * Verify a VLink code presented via /v link command.
     * 
     * @param code The VLink code
     * @param playerUuid The player's UUID
     * @param playerName The player's name
     * @return Verification result
     */
    public VLinkResult verifyCode(String code, UUID playerUuid, String playerName) {
        // Check if code was already used
        if (usedCodes.containsKey(code)) {
            return VLinkResult.denied("This code has already been used.");
        }
        
        // Find session by code
        String sessionId = codeToSession.get(code);
        if (sessionId == null) {
            return VLinkResult.denied("Invalid code.");
        }
        
        VLinkSession session = sessions.get(sessionId);
        if (session == null) {
            codeToSession.remove(code);
            return VLinkResult.denied("Session not found.");
        }
        
        // Check expiration
        if (session.isExpired()) {
            sessions.remove(sessionId);
            codeToSession.remove(code);
            return VLinkResult.denied("This code has expired.");
        }
        
        // Mark code as used
        usedCodes.put(code, System.currentTimeMillis());
        
        // Verify destination identity matches the player
        Identity destinationIdentity = resolveDestinationIdentity(playerUuid, playerName, session.getDestinationType());
        if (destinationIdentity == null) {
            return VLinkResult.denied("Could not resolve destination identity.");
        }
        
        // Complete the session
        session.complete(destinationIdentity);
        
        plugin.getLogger().info("[VLink] Code verified. Session " + sessionId + " completed.");
        
        return VLinkResult.verified(session);
    }

    /**
     * Get a session by ID.
     */
    public VLinkSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * Get a session by its short verification code.
     *
     * @param code The VLink code (e.g. "ABCD-EFGH")
     * @return The session, or null if no active session matches
     */
    public VLinkSession getSessionByCode(String code) {
        String sessionId = codeToSession.get(code);
        if (sessionId == null) {
            return null;
        }
        return sessions.get(sessionId);
    }

    /**
     * Get all active sessions.
     * Returned map is a live view — callers should not modify it.
     */
    public Map<String, VLinkSession> getSessions() {
        return sessions;
    }

    /**
     * Get pending session for a source UUID.
     */
    public VLinkSession getPendingSessionForSource(UUID sourceUuid) {
        return sessions.values().stream()
            .filter(s -> s.getSourceIdentity().getUuid().equals(sourceUuid))
            .filter(s -> s.getState() == VLinkState.PENDING)
            .findFirst()
            .orElse(null);
    }

    /**
     * Resolve destination identity from player info.
     * Returns null for BEDROCK if Floodgate is not available.
     */
    private Identity resolveDestinationIdentity(UUID playerUuid, String playerName, IdentityType type) {
        switch (type) {
            case PREMIUM:
                return plugin.getIdentityManager().getOrCreatePremium(playerName, playerUuid);
            case BEDROCK:
                if (!plugin.getIdentityManager().isBedrockEnabled()) {
                    return null;
                }
                return plugin.getIdentityManager().getOrCreateBedrock(playerName, playerUuid);
            default:
                return null;
        }
    }

    /**
     * Generate a cryptographically secure session ID.
     */
    private String generateSessionId() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return UUID.nameUUIDFromBytes(bytes).toString();
    }

    /**
     * Generate a short, user-friendly VLink code.
     * Format: XXXX-XXXX (8 chars with dash)
     */
    private String generateCode() {
        int codeLength = config.getVlinkCodeLength();
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // Removed confusing chars
        
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < codeLength; i++) {
            code.append(chars.charAt(random.nextInt(chars.length())));
            if (i == codeLength / 2 - 1 && codeLength > 4) {
                code.append('-');
            }
        }
        
        return code.toString();
    }

    /**
     * Start periodic cleanup of expired sessions and used codes.
     */
    private void startCleanupTask() {
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            long now = System.currentTimeMillis();
            
            // Clean expired sessions
            sessions.entrySet().removeIf(entry -> {
                VLinkSession session = entry.getValue();
                if (session.isExpired()) {
                    codeToSession.remove(session.getCode());
                    plugin.getLogger().info("[VLink] Expired session removed: " + entry.getKey());
                    return true;
                }
                return false;
            });
            
            // Clean old used codes (keep for 24 hours)
            usedCodes.entrySet().removeIf(entry -> 
                now - entry.getValue() > 24 * 60 * 60 * 1000);
            
        }, 20L * 60, 20L * 60); // Run every minute
    }

    // Inner classes
    public enum VLinkState {
        PENDING,      // Waiting for /v link command
        VERIFIED,     // Code verified, ready for migration
        COMPLETED,    // Migration completed
        EXPIRED,      // Session expired
        FAILED        // Verification failed
    }

    public static class VLinkSession {
        private final String sessionId;
        private final String code;
        private final Identity sourceIdentity;
        private final IdentityType destinationType;
        private final String pluginId;
        private final long createdAt;
        private final long expiresAt;
        
        private VLinkState state;
        private Identity destinationIdentity;
        private String failureReason;

        private String serverAddress;
        private String serverType;
        private List<String> migrationScope;

        public VLinkSession(String sessionId, String code, Identity sourceIdentity, 
                           IdentityType destinationType, String pluginId, int expiryMinutes) {
            this.sessionId = sessionId;
            this.code = code;
            this.sourceIdentity = sourceIdentity;
            this.destinationType = destinationType;
            this.pluginId = pluginId;
            this.createdAt = System.currentTimeMillis();
            this.expiresAt = createdAt + (expiryMinutes * 60L * 1000L);
            this.state = VLinkState.PENDING;
        }

        public void complete(Identity destinationIdentity) {
            this.destinationIdentity = destinationIdentity;
            this.state = VLinkState.COMPLETED;
        }

        public void fail(String reason) {
            this.failureReason = reason;
            this.state = VLinkState.FAILED;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }

        // Getters
        public String getSessionId() { return sessionId; }
        public String getCode() { return code; }
        public Identity getSourceIdentity() { return sourceIdentity; }
        public IdentityType getDestinationType() { return destinationType; }
        public String getPluginId() { return pluginId; }
        public long getCreatedAt() { return createdAt; }
        public long getExpiresAt() { return expiresAt; }
        public VLinkState getState() { return state; }
        public Identity getDestinationIdentity() { return destinationIdentity; }
        public String getFailureReason() { return failureReason; }

        public void setServerAddress(String serverAddress) { this.serverAddress = serverAddress; }
        public String getServerAddress() { return serverAddress; }

        public void setServerType(String serverType) { this.serverType = serverType; }
        public String getServerType() { return serverType; }

        public void setMigrationScope(List<String> migrationScope) { this.migrationScope = migrationScope; }
        public List<String> getMigrationScope() { return migrationScope; }
    }

    public static class VLinkResult {
        private final boolean success;
        private final boolean verified;
        private final String message;
        private final VLinkSession session;

        private VLinkResult(boolean success, boolean verified, String message, VLinkSession session) {
            this.success = success;
            this.verified = verified;
            this.message = message;
            this.session = session;
        }

        public static VLinkResult success(VLinkSession session) {
            return new VLinkResult(true, false, "Session created", session);
        }

        public static VLinkResult verified(VLinkSession session) {
            return new VLinkResult(true, true, "Code verified", session);
        }

        public static VLinkResult denied(String message) {
            return new VLinkResult(false, false, message, null);
        }

        public boolean isSuccess() { return success; }
        public boolean isVerified() { return verified; }
        public String getMessage() { return message; }
        public VLinkSession getSession() { return session; }
    }
}
