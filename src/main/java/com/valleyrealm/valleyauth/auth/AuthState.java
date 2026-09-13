package com.valleyrealm.valleyauth.auth;

/**
 * Authentication state for a connecting player.
 */
public enum AuthState {
    /** Player has not been checked yet */
    PENDING,
    /** Player is verified as Premium Java */
    PREMIUM,
    /** Player is a Bedrock connection via Floodgate */
    BEDROCK,
    /** Player is an Offline Java identity */
    OFFLINE,
    /** Player is authenticated and allowed to play */
    AUTHENTICATED,
    /** Player failed verification */
    FAILED
}
