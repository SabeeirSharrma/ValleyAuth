package com.valleyrealm.valleyauth.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * Represents a verified identity in Valley Auth.
 * 
 * Each identity is independently represented with its own UUID.
 * The canonical name includes the prefix (e.g., "-Sabeeir" for offline).
 */
public class Identity {

    private final String canonicalName;
    private final String username;
    private final IdentityType type;
    private final UUID uuid;

    /**
     * Create a new identity.
     * 
     * @param canonicalName Full name with prefix (e.g., "-Sabeeir")
     * @param type Identity type
     * @param uuid Unique UUID for this identity
     */
    public Identity(String canonicalName, IdentityType type, UUID uuid) {
        this.canonicalName = canonicalName;
        this.username = canonicalName.substring(type.getDefaultPrefix().length());
        this.type = type;
        this.uuid = uuid;
    }

    /**
     * Get the canonical name with prefix.
     */
    public String getCanonicalName() {
        return canonicalName;
    }

    /**
     * Get the username without prefix.
     */
    public String getUsername() {
        return username;
    }

    /**
     * Get the identity type.
     */
    public IdentityType getType() {
        return type;
    }

    /**
     * Get the UUID for this identity.
     */
    public UUID getUuid() {
        return uuid;
    }

    /**
     * Check if this identity requires manual authentication.
     */
    public boolean requiresAuth() {
        return type.requiresManualAuth();
    }

    /**
     * Check if this identity is platform-verified.
     */
    public boolean isVerified() {
        return type.isPlatformVerified();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Identity identity = (Identity) o;
        return type == identity.type && uuid.equals(identity.uuid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, uuid);
    }

    @Override
    public String toString() {
        return "Identity{" + canonicalName + ", type=" + type + ", uuid=" + uuid + "}";
    }
}
