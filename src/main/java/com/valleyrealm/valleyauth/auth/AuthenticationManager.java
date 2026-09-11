package com.valleyrealm.valleyauth.auth;

import com.valleyrealm.valleyauth.ValleyAuth;
import com.valleyrealm.valleyauth.config.ConfigManager;
import com.valleyrealm.valleyauth.identity.Identity;
import com.valleyrealm.valleyauth.identity.IdentityType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles player authentication based on identity type.
 * 
 * Authentication Matrix:
 * - Premium Java: Automatic (online-mode)
 * - Bedrock: Automatic (Floodgate)
 * - Offline Java: Password-based (/register, /login)
 * 
 * Security: Offline players must never impersonate Premium/Bedrock.
 */
public class AuthenticationManager {

    private final ValleyAuth plugin;
    private final ConfigManager config;

    // Tracks authentication state per player UUID
    private final Map<UUID, AuthState> authStateMap = new ConcurrentHashMap<>();

    // Pending registrations (UUID -> registration info)
    private final Map<UUID, RegistrationInfo> pendingRegistrations = new ConcurrentHashMap<>();

    public AuthenticationManager(ValleyAuth plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
    }

    /**
     * Handle player join event.
     * Determines authentication method based on identity type.
     */
    public AuthResult onPlayerJoin(String username, UUID playerUuid, IdentityType identityType) {
        switch (identityType) {
            case PREMIUM:
                // Premium Java - automatic authentication
                return AuthResult.automatic("Premium Java - automatic authentication");
            
            case BEDROCK:
                // Bedrock - automatic authentication via Floodgate
                return AuthResult.automatic("Bedrock - automatic authentication");
            
            case OFFLINE:
                // Offline Java - requires password authentication
                return handleOfflineJoin(username, playerUuid);
            
            default:
                return AuthResult.denied("Unknown identity type");
        }
    }

    /**
     * Handle offline player join.
     * Requires /register or /login within timeout.
     */
    private AuthResult handleOfflineJoin(String username, UUID playerUuid) {
        // Check if this username is protected by a Premium identity
        if (plugin.getIdentityManager().isUsernameProtected(username)) {
            return AuthResult.denied(
                "This username is protected by a Premium account. " +
                "Please connect with your Premium account instead.");
        }

        // Check if account exists
        if (plugin.getStorageManager().hasPassword(playerUuid.toString())) {
            // Account exists - needs /login
            authStateMap.put(playerUuid, AuthState.REQUIRES_LOGIN);
            return AuthResult.requiresLogin(
                "Account found. Please use /login <password> within " + 
                config.getLoginTimeoutSeconds() + " seconds.");
        } else {
            // New account - needs /register
            authStateMap.put(playerUuid, AuthState.REQUIRES_REGISTRATION);
            return AuthResult.requiresRegistration(
                "New account. Please use /register <password> within " +
                config.getLoginTimeoutSeconds() + " seconds.");
        }
    }

    /**
     * Handle /register command.
     */
    public AuthResult handleRegister(UUID playerUuid, String password) {
        // Validate password
        if (password.length() < config.getMinPasswordLength()) {
            return AuthResult.denied("Password must be at least " + config.getMinPasswordLength() + " characters.");
        }
        if (password.length() > config.getMaxPasswordLength()) {
            return AuthResult.denied("Password must be no more than " + config.getMaxPasswordLength() + " characters.");
        }

        // Check if already registered
        if (plugin.getStorageManager().hasPassword(playerUuid.toString())) {
            return AuthResult.denied("Account already registered. Use /login instead.");
        }

        // Check if in correct state
        AuthState state = authStateMap.get(playerUuid);
        if (state != AuthState.REQUIRES_REGISTRATION) {
            return AuthResult.denied("Cannot register at this time.");
        }

        // Store password (in production, use proper hashing)
        String hashedPassword = hashPassword(password);
        plugin.getStorageManager().storePasswordHash(playerUuid.toString(), hashedPassword);
        
        // Mark as authenticated
        authStateMap.put(playerUuid, AuthState.AUTHENTICATED);
        
        plugin.getLogger().info("[Valley Auth] Account registered for UUID: " + playerUuid);
        
        return AuthResult.success("Account registered successfully.");
    }

    /**
     * Handle /login command.
     */
    public AuthResult handleLogin(UUID playerUuid, String password) {
        // Check if account exists
        if (!plugin.getStorageManager().hasPassword(playerUuid.toString())) {
            return AuthResult.denied("Account not found. Use /register first.");
        }

        // Check if in correct state
        AuthState state = authStateMap.get(playerUuid);
        if (state != AuthState.REQUIRES_LOGIN) {
            return AuthResult.denied("Cannot login at this time.");
        }

        // Verify password
        String storedHash = plugin.getStorageManager().getPasswordHash(playerUuid.toString());
        if (!verifyPassword(password, storedHash)) {
            return AuthResult.denied("Incorrect password.");
        }

        // Mark as authenticated
        authStateMap.put(playerUuid, AuthState.AUTHENTICATED);
        
        return AuthResult.success("Logged in successfully.");
    }

    /**
     * Check if a player is authenticated.
     */
    public boolean isAuthenticated(UUID playerUuid) {
        AuthState state = authStateMap.get(playerUuid);
        return state == AuthState.AUTHENTICATED;
    }

    /**
     * Handle player disconnect.
     */
    public void onPlayerDisconnect(UUID playerUuid) {
        authStateMap.remove(playerUuid);
        pendingRegistrations.remove(playerUuid);
    }

    private static final String HASH_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int HASH_ITERATIONS = 100_000;
    private static final int HASH_KEY_LENGTH = 256;
    private static final int SALT_LENGTH = 16;
    private static final String HASH_SEPARATOR = ":";

    private String hashPassword(String password) {
        try {
            byte[] salt = new byte[SALT_LENGTH];
            java.security.SecureRandom.getInstanceStrong().nextBytes(salt);

            javax.crypto.SecretKeyFactory factory = javax.crypto.SecretKeyFactory.getInstance(HASH_ALGORITHM);
            javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                password.toCharArray(), salt, HASH_ITERATIONS, HASH_KEY_LENGTH);
            java.security.Key key = factory.generateSecret(spec);
            byte[] hash = key.getEncoded();

            spec.clearPassword();

            String saltHex = bytesToHex(salt);
            String hashHex = bytesToHex(hash);
            return saltHex + HASH_SEPARATOR + hashHex;
        } catch (Exception e) {
            throw new RuntimeException("Password hashing failed", e);
        }
    }

    private boolean verifyPassword(String password, String storedHash) {
        try {
            String[] parts = storedHash.split(HASH_SEPARATOR, 2);
            if (parts.length != 2) return false;

            byte[] salt = hexToBytes(parts[0]);
            byte[] expectedHash = hexToBytes(parts[1]);

            javax.crypto.SecretKeyFactory factory = javax.crypto.SecretKeyFactory.getInstance(HASH_ALGORITHM);
            javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                password.toCharArray(), salt, HASH_ITERATIONS, expectedHash.length * 8);
            java.security.Key key = factory.generateSecret(spec);
            byte[] actualHash = key.getEncoded();

            spec.clearPassword();

            return java.security.MessageDigest.isEqual(expectedHash, actualHash);
        } catch (Exception e) {
            return false;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] bytes = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            bytes[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return bytes;
    }

    // Enums and inner classes
    public enum AuthState {
        REQUIRES_REGISTRATION,
        REQUIRES_LOGIN,
        AUTHENTICATED
    }

    public static class AuthResult {
        private final boolean success;
        private final boolean requiresLogin;
        private final boolean requiresRegistration;
        private final String message;

        private AuthResult(boolean success, boolean requiresLogin, boolean requiresRegistration, String message) {
            this.success = success;
            this.requiresLogin = requiresLogin;
            this.requiresRegistration = requiresRegistration;
            this.message = message;
        }

        public static AuthResult success(String message) {
            return new AuthResult(true, false, false, message);
        }

        public static AuthResult automatic(String message) {
            return new AuthResult(true, false, false, message);
        }

        public static AuthResult requiresLogin(String message) {
            return new AuthResult(false, true, false, message);
        }

        public static AuthResult requiresRegistration(String message) {
            return new AuthResult(false, false, true, message);
        }

        public static AuthResult denied(String message) {
            return new AuthResult(false, false, false, message);
        }

        public boolean isSuccess() { return success; }
        public boolean isRequiresLogin() { return requiresLogin; }
        public boolean isRequiresRegistration() { return requiresRegistration; }
        public String getMessage() { return message; }
    }

    public static class RegistrationInfo {
        private final String username;
        private final UUID uuid;
        private final long timestamp;

        public RegistrationInfo(String username, UUID uuid) {
            this.username = username;
            this.uuid = uuid;
            this.timestamp = System.currentTimeMillis();
        }

        public String getUsername() { return username; }
        public UUID getUuid() { return uuid; }
        public long getTimestamp() { return timestamp; }
    }
}
