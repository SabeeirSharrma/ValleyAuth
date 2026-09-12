package com.valleyrealm.valleyauth.cert;

import java.util.Collections;
import java.util.Date;
import java.util.List;

public class PluginCertificate {

    private String certificateId;
    private String pluginId;
    private List<String> capabilities;
    private Date issuanceDate;
    private Date expirationDate;
    private String issuer;
    private String signature;
    private String status;
    private String encryptedRevocationTimestamp;

    public PluginCertificate(String certificateId, String pluginId, List<String> capabilities,
                             Date issuanceDate, Date expirationDate, String issuer,
                             String signature, String status, String encryptedRevocationTimestamp) {
        this.certificateId = certificateId;
        this.pluginId = pluginId;
        this.capabilities = capabilities != null ? List.copyOf(capabilities) : List.of();
        this.issuanceDate = issuanceDate != null ? new Date(issuanceDate.getTime()) : new Date();
        this.expirationDate = expirationDate != null ? new Date(expirationDate.getTime()) : new Date();
        this.issuer = issuer;
        this.signature = signature;
        this.status = status;
        this.encryptedRevocationTimestamp = encryptedRevocationTimestamp;
    }

    public String getCertificateId() { return certificateId; }
    public String getPluginId() { return pluginId; }
    public List<String> getCapabilities() { return capabilities; }
    public Date getIssuanceDate() { return new Date(issuanceDate.getTime()); }
    public Date getExpirationDate() { return new Date(expirationDate.getTime()); }
    public String getIssuer() { return issuer; }
    public String getSignature() { return signature; }
    public String getStatus() { return status; }
    public String getEncryptedRevocationTimestamp() { return encryptedRevocationTimestamp; }
}
