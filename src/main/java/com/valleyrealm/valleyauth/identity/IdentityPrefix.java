package com.valleyrealm.valleyauth.identity;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages identity prefixes.
 * Section 4 of the spec.
 *
 * Default prefixes:
 *   premium-prefix: ""
 *   bedrock-prefix: "."
 *   offline-prefix: "-"
 *
 * The supplied username/prefix must NOT be treated as authoritative proof
 * of identity type. Valley Auth determines the actual connection type first
 * and then maps it to its canonical identity.
 */
public class IdentityPrefix {

    private final Map<IdentityType, String> prefixes;

    public IdentityPrefix() {
        this.prefixes = new HashMap<>();
        for (IdentityType type : IdentityType.values()) {
            prefixes.put(type, type.getDefaultPrefix());
        }
    }

    /**
     * Get the prefix for an identity type.
     */
    public String getPrefix(IdentityType type) {
        return prefixes.getOrDefault(type, "");
    }

    /**
     * Set a custom prefix for an identity type.
     */
    public void setPrefix(IdentityType type, String prefix) {
        prefixes.put(type, prefix);
    }

    /**
     * Apply prefix to a username.
     * Example: applyPrefix(OFFLINE, "Sabeeir") → "-Sabeeir"
     */
    public String applyPrefix(IdentityType type, String username) {
        return getPrefix(type) + username;
    }

    /**
     * Strip prefix from a canonical username.
     * Example: stripPrefix("-Sabeeir") → "Sabeeir"
     */
    public String stripPrefix(String canonicalName) {
        for (IdentityType type : IdentityType.values()) {
            String prefix = getPrefix(type);
            if (!prefix.isEmpty() && canonicalName.startsWith(prefix)) {
                return canonicalName.substring(prefix.length());
            }
        }
        return canonicalName;
    }
}
