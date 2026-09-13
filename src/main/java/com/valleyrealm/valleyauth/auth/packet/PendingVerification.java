package com.valleyrealm.valleyauth.auth.packet;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;

import java.util.UUID;

/**
 * State for an in-flight Mojang verification handshake.
 *
 * Created when a LOGIN_START is intercepted for a Mojang-account holder.
 * Cleaned up on success, failure, timeout, or disconnect.
 *
 * Section 79.4 Steps 3-5.
 */
public final class PendingVerification {

    private final String username;
    private final UUID clientUuid;
    private final byte[] verifyToken;
    private final ClientVersion clientVersion;
    private final long createdAt;
    private final User user;

    public PendingVerification(String username, UUID clientUuid, byte[] verifyToken,
                               ClientVersion clientVersion, long createdAt, User user) {
        this.username = username;
        this.clientUuid = clientUuid;
        this.verifyToken = verifyToken;
        this.clientVersion = clientVersion;
        this.createdAt = createdAt;
        this.user = user;
    }

    public String getUsername() {
        return username;
    }

    public UUID getClientUuid() {
        return clientUuid;
    }

    public byte[] getVerifyToken() {
        return verifyToken;
    }

    public ClientVersion getClientVersion() {
        return clientVersion;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public User getUser() {
        return user;
    }
}
