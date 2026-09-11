package com.valleyrealm.valleyauth.cert;

import java.util.Date;
import java.util.List;

/**
 * Represents a validated certificate returned from ValleyCertAPI.
 * Used by ValleyAuth Core for local certificate caching and capability checks.
 *
 * Includes the encrypted revocation timestamp from the CA — never plaintext.
 */
public class PluginCertificate {

    private final String certificateId;
    private final String pluginId;
    private final List<String> capabilities;
    private final Date issuanceDate;
    private final Date expirationDate;
    private final String issuer;
    private final String signature;
    private final String status;
    private final String encryptedRevocationTimestamp;

    public PluginCertificate(String certificateId, String pluginId, List<String> capabilities,
                             Date issuanceDate, Date expirationDate, String issuer,
                             String signature, String status, String encryptedRevocationTimestamp) {
        this.certificateId = certificateId;
        this.pluginId = pluginId;
        this.capabilities = capabilities;
        this.issuanceDate = issuanceDate;
        this.expirationDate = expirationDate;
        this.issuer = issuer;
        this.signature = signature;
        this.status = status;
        this.encryptedRevocationTimestamp = encryptedRevocationTimestamp;
    }

    public String getCertificateId() { return certificateId; }
    public String getPluginId() { return pluginId; }
    public List<String> getCapabilities() { return capabilities; }
    public Date getIssuanceDate() { return issuanceDate; }
    public Date getExpirationDate() { return expirationDate; }
    public String getIssuer() { return issuer; }
    public String getSignature() { return signature; }
    public String getStatus() { return status; }
    public String getEncryptedRevocationTimestamp() { return encryptedRevocationTimestamp; }
}
