package com.valleyrealm.valleyauth.migration;

/**
 * Migration types supported by Valley Auth Core.
 * 
 * Only migrations where ValleyAuth can establish sufficient ownership
 * are supported by core.
 */
public enum MigrationType {
    /**
     * Offline Java -> Premium Java
     * Allowed: source has ValleyAuth password, destination is platform verified.
     */
    OFFLINE_TO_PREMIUM(true),
    
    /**
     * Offline Java -> Bedrock
     * Allowed: source has ValleyAuth password, destination is Floodgate verified.
     */
    OFFLINE_TO_BEDROCK(true),
    
    /**
     * Offline Java -> Offline Java
     * Forbidden: prevents account-to-account transfers.
     */
    OFFLINE_TO_OFFLINE(false),
    
    /**
     * Premium Java -> Offline Java
     * Forbidden: would weaken security.
     */
    PREMIUM_TO_OFFLINE(false),
    
    /**
     * Bedrock -> Offline Java
     * Forbidden: would weaken security.
     */
    BEDROCK_TO_OFFLINE(false),
    
    /**
     * Premium Java -> Bedrock
     * Forbidden by core: both are platform-verified, no password to prove ownership.
     * May be supported by addons using VLink.
     */
    PREMIUM_TO_BEDROCK(false),
    
    /**
     * Bedrock -> Premium Java
     * Forbidden by core: same reason as above.
     */
    BEDROCK_TO_PREMIUM(false),
    
    /**
     * Premium Java -> Premium Java
     * Forbidden: no sense in migrating between same type.
     */
    PREMIUM_TO_PREMIUM(false),
    
    /**
     * Bedrock -> Bedrock
     * Forbidden: no sense in migrating between same type.
     */
    BEDROCK_TO_BEDROCK(false);

    private final boolean coreSupported;

    MigrationType(boolean coreSupported) {
        this.coreSupported = coreSupported;
    }

    /**
     * Check if this migration type is supported by ValleyAuth Core.
     */
    public boolean isCoreSupported() {
        return coreSupported;
    }
}
