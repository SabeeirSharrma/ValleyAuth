package com.valleyrealm.valleyauth.cert;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.valleyrealm.valleyauth.ValleyAuth;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * ValleyCert client integration for ValleyAuth Core.
 *
 * Acts as the intermediary between plugins and the ValleyCertAPI CA.
 * ValleyAuth Core obtains its own certificate from the CA, then validates
 * plugin certificate requests against the CA before granting capabilities.
 */
public class ValleyCertClient {

    private final ValleyAuth plugin;
    private final Gson gson;
    private final HttpClient httpClient;
    private final Map<String, PluginCertificate> cachedCertificates = new ConcurrentHashMap<>();

    private boolean initialized = false;
    private String apiUrl;

    public ValleyCertClient(ValleyAuth plugin) {
        this.plugin = plugin;
        this.gson = new GsonBuilder().create();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();
    }

    /**
     * Initialize the ValleyCert client.
     *
     * 1. Load API URL from config
     * 2. Check for existing core certificate on disk
     * 3. If missing, request one from ValleyCertAPI
     * 4. Load cached plugin certificates
     */
    public void initialize() {
        plugin.getLogger().info("[ValleyCert Client] Initializing...");

        apiUrl = plugin.getConfig().getString("certificate.api-url", "");

        if (apiUrl == null || apiUrl.isBlank()) {
            plugin.getLogger().warning("[ValleyCert Client] No certificate API URL configured.");
            plugin.getLogger().warning("[ValleyCert Client] Certificate validation will operate in offline/mock mode.");
            initialized = true;
            return;
        }

        ensureCoreCertificate();
        loadCachedCertificates();

        initialized = true;
        plugin.getLogger().info("[ValleyCert Client] Initialized. API: " + apiUrl);
    }

    /**
     * Ensure ValleyAuth Core has a valid certificate from the CA.
     */
    private void ensureCoreCertificate() {
        Path certFile = plugin.getDataFolder().toPath().resolve("data").resolve("core-cert.json");
        if (Files.exists(certFile)) {
            try {
                String json = Files.readString(certFile);
                PluginCertificate cached = gson.fromJson(json, PluginCertificate.class);
                if (cached != null && isCertificateValid(cached)) {
                    plugin.getLogger().info("[ValleyCert Client] Core certificate loaded: " + cached.getCertificateId());
                    cachedCertificates.put("core", cached);
                    return;
                }
                plugin.getLogger().warning("[ValleyCert Client] Core certificate expired or invalid, requesting renewal.");
            } catch (IOException e) {
                plugin.getLogger().warning("[ValleyCert Client] Failed to read core certificate: " + e.getMessage());
            }
        }

        PluginCertificate coreCert = requestCertificateFromCA(
            "valleyauth-core",
            List.of("VLINK", "IDENTITY_LINK", "RANK_SHARE", "MIGRATION_PROVIDER", "MIGRATION_ACCESS", "CERTIFICATE_MANAGEMENT"),
            90
        );

        if (coreCert != null) {
            cachedCertificates.put("core", coreCert);
            saveCertificateToFile("core-cert.json", coreCert);
            plugin.getLogger().info("[ValleyCert Client] Core certificate obtained: " + coreCert.getCertificateId());
        } else {
            plugin.getLogger().severe("[ValleyCert Client] Failed to obtain core certificate from CA!");
            plugin.getLogger().severe("[ValleyCert Client] Protected features may not work correctly.");
        }
    }

    /**
     * Request a certificate from ValleyCertAPI.
     */
    public PluginCertificate requestCertificateFromCA(String pluginId, List<String> capabilities, int validityDays) {
        try {
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("pluginId", pluginId);
            requestBody.add("capabilities", gson.toJsonTree(capabilities));
            requestBody.addProperty("requestedValidityDays", validityDays);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "/api/certificate/issue"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
                .timeout(java.time.Duration.ofSeconds(10))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonObject respBody = gson.fromJson(response.body(), JsonObject.class);
                if (respBody.get("success").getAsBoolean()) {
                    JsonObject certJson = respBody.getAsJsonObject("certificate");
                    return parsePluginCertificate(certJson);
                } else {
                    plugin.getLogger().warning("[ValleyCert Client] CA rejected request: " + respBody.get("message").getAsString());
                }
            } else {
                plugin.getLogger().warning("[ValleyCert Client] CA returned HTTP " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            plugin.getLogger().warning("[ValleyCert Client] CA request failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * Validate a plugin's certificate against the CA.
     */
    public boolean validatePluginCertificate(String pluginId, String capability) {
        if (!initialized) {
            plugin.getLogger().warning("[ValleyCert Client] Not initialized.");
            return false;
        }

        if (apiUrl == null || apiUrl.isBlank()) {
            if (plugin.getConfigManager().isEnforceCertificateAuthorization()) {
                plugin.getLogger().warning("[ValleyCert Client] No API configured but certificate enforcement is ON. Denying " + pluginId);
                return false;
            }
            return true;
        }

        PluginCertificate cached = cachedCertificates.get(pluginId);
        if (cached != null && isCertificateValid(cached)) {
            return hasCapability(cached, capability);
        }

        return validateViaAPI(pluginId, capability);
    }

    /**
     * Validate a certificate against the CA REST endpoint.
     */
    private boolean validateViaAPI(String pluginId, String capability) {
        try {
            PluginCertificate cert = requestCertificateFromCA(pluginId, List.of(capability), 90);
            if (cert == null) return false;

            cachedCertificates.put(pluginId, cert);
            return hasCapability(cert, capability);
        } catch (Exception e) {
            plugin.getLogger().warning("[ValleyCert Client] Validation failed for " + pluginId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Store a plugin certificate locally (called by ValleyCert utility).
     */
    public void storePluginCertificate(String pluginId, PluginCertificate certificate) {
        cachedCertificates.put(pluginId, certificate);
        saveCertificateToFile(pluginId + "-cert.json", certificate);
        plugin.getLogger().info("[ValleyCert Client] Stored certificate for " + pluginId);
    }

    /**
     * Check if the client is initialized.
     */
    public boolean isInitialized() {
        return initialized;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    // --- Internal helpers ---

    private boolean isCertificateValid(PluginCertificate cert) {
        if (cert.getExpirationDate() == null) return false;
        Date now = new Date();
        return now.before(cert.getExpirationDate());
    }

    private boolean hasCapability(PluginCertificate cert, String capability) {
        return cert.getCapabilities() != null &&
               cert.getCapabilities().stream()
                   .anyMatch(c -> c.equalsIgnoreCase(capability));
    }

    private PluginCertificate parsePluginCertificate(JsonObject certJson) {
        String certificateId = certJson.get("certificateId").getAsString();
        String pluginId = certJson.get("pluginId").getAsString();
        List<String> capabilities = gson.fromJson(certJson.get("capabilities"),
            new com.google.gson.reflect.TypeToken<List<String>>() {}.getType());
        Date issuanceDate = new Date(certJson.get("issuanceDate").getAsLong());
        Date expirationDate = new Date(certJson.get("expirationDate").getAsLong());
        String issuer = certJson.get("issuer").getAsString();
        String signature = certJson.has("signature") ? certJson.get("signature").getAsString() : "";
        String status = certJson.has("status") ? certJson.get("status").getAsString() : "ACTIVE";
        String encryptedRevocationTimestamp = certJson.has("encryptedRevocationTimestamp") ?
            certJson.get("encryptedRevocationTimestamp").getAsString() : null;

        return new PluginCertificate(certificateId, pluginId, capabilities, issuanceDate, expirationDate, issuer, signature, status, encryptedRevocationTimestamp);
    }

    private void loadCachedCertificates() {
        Path dataDir = plugin.getDataFolder().toPath().resolve("data");
        if (!Files.exists(dataDir)) return;

        try (var stream = Files.list(dataDir)) {
            stream.filter(p -> p.toString().endsWith("-cert.json"))
                .forEach(this::loadCertificateFile);
        } catch (IOException e) {
            plugin.getLogger().warning("[ValleyCert Client] Failed to scan certificate cache: " + e.getMessage());
        }
    }

    private void loadCertificateFile(Path path) {
        try {
            String json = Files.readString(path);
            PluginCertificate cert = gson.fromJson(json, PluginCertificate.class);
            if (cert != null && cert.getPluginId() != null) {
                cachedCertificates.put(cert.getPluginId(), cert);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("[ValleyCert Client] Failed to load certificate: " + path.getFileName());
        }
    }

    private void saveCertificateToFile(String filename, PluginCertificate cert) {
        Path certFile = plugin.getDataFolder().toPath().resolve("data").resolve(filename);
        try {
            Files.createDirectories(certFile.getParent());
            Files.writeString(certFile, gson.toJson(cert));
        } catch (IOException e) {
            plugin.getLogger().warning("[ValleyCert Client] Failed to save certificate: " + e.getMessage());
        }
    }
}
