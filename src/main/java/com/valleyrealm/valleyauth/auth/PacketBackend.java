package com.valleyrealm.valleyauth.auth;

/**
 * Stage 79.4 Step 2: Packet library selection.
 * packetevents is primary, ProtocolLib is fallback.
 * NONE = forced auth disabled (fail closed).
 */
public enum PacketBackend {
    PACKETEVENTS,
    PROTOCOL_LIB,
    NONE
}
