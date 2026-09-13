package com.valleyrealm.valleyauth.session;

import com.valleyrealm.valleyauth.ValleyAuthPlugin;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * Mojang session verification utility for forced authentication.
 * Section 79.4 Steps 3-5 of the spec.
 *
 * This class provides:
 * - RSA keypair generation for the encryption handshake
 * - Server hash computation for Mojang's hasJoined endpoint
 *
 * The actual packet interception and Mojang API calls are handled by the
 * packet listener implementations:
 * - {@link com.valleyrealm.valleyauth.auth.packet.PacketEventsAuthListener} (primary)
 * - {@link com.valleyrealm.valleyauth.auth.packet.ProtocolLibAuthListener} (fallback)
 *
 * The server's RSA keypair is generated once at plugin startup and shared
 * across all connections. The private key is used to decrypt the client's
 * shared secret during the encryption handshake.
 */
public class MojangSessionVerifier {

    private final ValleyAuthPlugin plugin;
    private final KeyPair rsaKeyPair;
    private final SecureRandom secureRandom;

    public MojangSessionVerifier(ValleyAuthPlugin plugin) {
        this.plugin = plugin;
        this.secureRandom = new SecureRandom();

        // Generate RSA keypair for encryption
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(1024, secureRandom);
            this.rsaKeyPair = keyGen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("RSA not available", e);
        }
    }

    /**
     * Compute the server hash for Mojang's session server.
     *
     * The hash is: SHA-1("" + sharedSecret + publicKey) encoded as signed hex.
     * The empty string is encoded as ISO-8859-1 (which is just empty bytes).
     * The result uses BigInteger.toString(16) which produces signed hex
     * (two's complement), matching Mojang's expected format.
     *
     * @param sharedSecret The decrypted shared secret from the client's EncryptionResponse
     * @param publicKey    The server's RSA public key (DER-encoded bytes)
     * @return The server hash as a signed hex string
     */
    public String computeServerHash(byte[] sharedSecret, byte[] publicKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update("".getBytes(StandardCharsets.ISO_8859_1));
            sha1.update(sharedSecret);
            sha1.update(publicKey);

            // Convert to signed hex (two's complement BigInteger.toString(16))
            return new java.math.BigInteger(sha1.digest()).toString(16);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 not available", e);
        }
    }

    /**
     * Get the server's RSA keypair.
     * Used by packet listeners to:
     * - Send the public key in EncryptionRequest
     * - Decrypt the client's shared secret with the private key
     *
     * @return The RSA keypair
     */
    public KeyPair getRsaKeyPair() {
        return rsaKeyPair;
    }
}
