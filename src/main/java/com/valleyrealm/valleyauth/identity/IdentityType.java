package com.valleyrealm.valleyauth.identity;

/**
 * Identity types recognized by Valley Auth.
 * 
 * Each type has distinct authentication requirements and UUID handling.
 */
public enum IdentityType {
    /**
     * Verified through Minecraft's online authentication.
     * Uses official Premium/Mojang UUID.
     * No Valley Auth password required.
     * Automatically authenticated.
     */
    PREMIUM("premium", ""),
    
    /**
     * Identified through Floodgate.
     * Uses Floodgate UUID.
     * No Valley Auth password required.
     * Automatically authenticated.
     */
    BEDROCK("bedrock", "."),
    
    /**
     * Used for Offline/Cracked Java connections.
     * Uses Valley Auth-generated persistent UUID.
     * Protected by Valley Auth password.
     * Requires /register and /login.
     */
    OFFLINE("offline", "-");

    private final String name;
    private final String defaultPrefix;

    IdentityType(String name, String defaultPrefix) {
        this.name = name;
        this.defaultPrefix = defaultPrefix;
    }

    public String getName() {
        return name;
    }

    public String getDefaultPrefix() {
        return defaultPrefix;
    }

    /**
     * Check if this identity type requires manual authentication.
     * Premium and Bedrock are automatically authenticated.
     */
    public boolean requiresManualAuth() {
        return this == OFFLINE;
    }

    /**
     * Check if this identity type uses Valley Auth passwords.
     */
    public boolean usesPassword() {
        return this == OFFLINE;
    }

    /**
     * Check if this identity type is platform-verified.
     */
    public boolean isPlatformVerified() {
        return this == PREMIUM || this == BEDROCK;
    }
}
