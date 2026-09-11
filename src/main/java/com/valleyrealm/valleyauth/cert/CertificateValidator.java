package com.valleyrealm.valleyauth.cert;

import com.valleyrealm.valleyauth.ValleyAuth;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.Set;

/**
 * Validates Valley Auth certificates.
 *
 * Used by ValleyAuth Core to verify that addons/plugins are authorized
 * to perform protected operations.
 *
 * Revocation check (per spec):
 *   Decrypt revocation timestamp → elapsed = now - timestamp → compare against
 *   the certificate's allowed lifetime. If elapsed exceeds the lifetime, treat
 *   the certificate as invalid. Fail closed on any decrypt/parse error.
 */
public class CertificateValidator {

    private final ValleyAuth plugin;

    private static final Set<String> CORE_CAPABILITIES = Set.of(
        "VLINK", "IDENTITY_LINK", "RANK_SHARE",
        "MIGRATION_PROVIDER", "MIGRATION_ACCESS", "CERTIFICATE_MANAGEMENT"
    );
    private static final int GCM_TAG_LENGTH = 128;

    public CertificateValidator(ValleyAuth plugin) {
        this.plugin = plugin;
    }

    /**
     * Validate that a plugin has the required capability.
     */
    public boolean isValid(String pluginId, String requiredCapability) {
        plugin.getLogger().info("[ValleyAuth Cert] Validating " + pluginId + " for capability " + requiredCapability);

        if (!plugin.getConfigManager().isEnforceCertificateAuthorization()) {
            plugin.getLogger().info("[ValleyAuth Cert] Certificate enforcement is disabled. Allowing " + pluginId);
            return true;
        }

        if (requiredCapability == null || requiredCapability.isBlank()) {
            plugin.getLogger().warning("[ValleyAuth Cert] Null or empty capability requested.");
            return false;
        }

        if (pluginId == null || pluginId.isBlank()) {
            plugin.getLogger().warning("[ValleyAuth Cert] Null or empty pluginId.");
            return false;
        }

        if (pluginId.equals("valleyauth-core") && CORE_CAPABILITIES.contains(requiredCapability)) {
            return true;
        }

        PluginCertificate cert = plugin.getValleyCertClient().getCachedCertificate(pluginId);
        if (cert != null) {
            if (!validateCertificate(cert, requiredCapability)) {
                return false;
            }
            if (plugin.getValleyCertClient().isCaAvailable() && cert.getCertificateId() != null) {
                return plugin.getValleyCertClient().validateViaCA(cert.getCertificateId());
            }
            plugin.getLogger().info("[ValleyAuth Cert] CA offline — trusting cached cert for " + pluginId);
            return true;
        }

        return plugin.getValleyCertClient().validatePluginCertificate(pluginId, requiredCapability);
    }

    /**
     * Validate a certificate with encrypted-timestamp revocation check.
     *
     * Per spec: decrypt revocation timestamp → elapsed = now - timestamp
     * → compare against the certificate's allowed lifetime.
     * Fail closed on any decrypt/parse error.
     */
    public boolean validateCertificate(PluginCertificate cert, String requiredCapability) {
        if (cert == null) return false;
        if (requiredCapability == null || requiredCapability.isBlank()) return false;

        // Check expiry
        if (cert.getExpirationDate() != null && new Date().after(cert.getExpirationDate())) {
            plugin.getLogger().warning("[ValleyAuth Cert] Certificate expired: " + cert.getCertificateId());
            return false;
        }

        // Check encrypted revocation timestamp (fail closed on error)
        String encryptedTimestamp = cert.getEncryptedRevocationTimestamp();
        if (encryptedTimestamp != null) {
            try {
                long revocationTime = decryptTimestamp(encryptedTimestamp);
                long now = System.currentTimeMillis();
                long elapsed = now - revocationTime;
                long allowedLifetimeMs = cert.getExpirationDate().getTime() - cert.getIssuanceDate().getTime();

                if (elapsed > allowedLifetimeMs) {
                    plugin.getLogger().warning("[ValleyAuth Cert] Certificate revoked and lifetime exceeded: " + cert.getCertificateId());
                    return false;
                }
            } catch (Exception e) {
                plugin.getLogger().severe("[ValleyAuth Cert] Failed to decrypt revocation timestamp — failing closed: " + cert.getCertificateId());
                return false;
            }
        }

        // Check capability
        if (cert.getCapabilities() == null ||
            cert.getCapabilities().stream().noneMatch(c -> c.equalsIgnoreCase(requiredCapability))) {
            plugin.getLogger().warning("[ValleyAuth Cert] Certificate missing capability " + requiredCapability);
            return false;
        }

        return true;
    }

    public boolean isExpired(PluginCertificate cert) {
        if (cert == null || cert.getExpirationDate() == null) return true;
        return new Date().after(cert.getExpirationDate());
    }

    /**
     * Check if a certificate has been revoked (has a stored encrypted revocation timestamp).
     */
    public boolean isRevoked(PluginCertificate cert) {
        if (cert == null) return true;
        return cert.getEncryptedRevocationTimestamp() != null;
    }

    public Map<String, String> getCertificateStatus() {
        ValleyCertClient client = plugin.getValleyCertClient();
        PluginCertificate coreCert = client.getCoreCertificate();

        String caStatus = client.isCaAvailable() ? "Online" : "Offline";

        if (coreCert == null) {
            return Map.of(
                "coreCert", "None",
                "caStatus", caStatus,
                "apiUrl", client.getApiUrl() != null ? client.getApiUrl() : "Not configured"
            );
        }

        String certStatus;
        if (isExpired(coreCert)) {
            certStatus = "Expired";
        } else if (isRevoked(coreCert)) {
            certStatus = "Revoked";
        } else {
            certStatus = "Valid";
        }

        return Map.of(
            "coreCert", coreCert.getCertificateId(),
            "certStatus", certStatus,
            "pluginId", coreCert.getPluginId() != null ? coreCert.getPluginId() : "N/A",
            "expires", coreCert.getExpirationDate() != null ? coreCert.getExpirationDate().toString() : "N/A",
            "caStatus", caStatus,
            "apiUrl", client.getApiUrl() != null ? client.getApiUrl() : "Not configured"
        );
    }

    /**
     * Decrypt a timestamp encrypted by the CA.
     * Uses AES-GCM with the same revocation key the CA used.
     * In production, this key should be obtained from a secure source.
     * For now, reads from the CA's key directory.
     */
    private long decryptTimestamp(String encryptedBase64) throws Exception {
        byte[] combined = Base64.getDecoder().decode(encryptedBase64);

        byte[] iv = new byte[12];
        byte[] ciphertext = new byte[combined.length - 12];
        System.arraycopy(combined, 0, iv, 0, 12);
        System.arraycopy(combined, 12, ciphertext, 0, ciphertext.length);

        java.nio.file.Path keyPath = plugin.getDataFolder().toPath()
            .resolve("data").resolve("keys").resolve("revocation.key");
        byte[] keyBytes = java.nio.file.Files.readAllBytes(keyPath);
        SecretKeySpec key = new SecretKeySpec(Base64.getDecoder().decode(new String(keyBytes).trim()), "AES");

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, key, spec);

        byte[] plaintext = cipher.doFinal(ciphertext);
        return Long.parseLong(new String(plaintext));
    }
}
