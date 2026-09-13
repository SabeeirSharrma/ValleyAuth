package com.valleyrealm.valleyauth.identity;

/**
 * Identity types recognized by Valley Auth.
 * Section 3 of the spec.
 */
public enum IdentityType {
    /**
     * Premium Java — verified through Minecraft's online authentication.
     * Uses the official Premium/Mojang UUID.
     * Canonical representation: Name (no prefix)
     */
    PREMIUM(""),

    /**
     * Bedrock — identified through Floodgate.
     * Uses the Floodgate UUID.
     * Canonical representation: .Name (Floodgate adds '.' prefix)
     */
    BEDROCK("."),

    /**
     * Offline Java — used for Offline/Cracked Java connections.
     * Uses a Valley Auth-generated persistent UUID.
     * Canonical representation: -Name (Valley Auth adds '-' prefix)
     */
    OFFLINE("-");

    private final String defaultPrefix;

    IdentityType(String defaultPrefix) {
        this.defaultPrefix = defaultPrefix;
    }

    public String getDefaultPrefix() {
        return defaultPrefix;
    }
}
