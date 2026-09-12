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
// allow: SIZE_OK — single-responsibility CA client (HTTP + cache + lifecycle in one cohesive unit)
public class ValleyCertClient {

    private final ValleyAuth plugin;
    private final Gson gson;
    private final HttpClient httpClient;
    private final Map<String, PluginCertificate> cachedCertificates = new ConcurrentHashMap<>();

    private boolean initialized = false;
    private volatile boolean caAvailable = false;
    private String apiUrl;
    private String docsUrl;
    private static final int MAX_INIT_RETRIES = 3;
    private static final long RETRY_BASE_DELAY_MS = 2000;

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

        apiUrl = plugin.getConfigManager().getCertificateApiUrl();
        docsUrl = plugin.getConfigManager().getCertificateDocsUrl();

        if (apiUrl == null || apiUrl.isBlank()) {
            plugin.getLogger().warning("[ValleyCert Client] No certificate API configured. Certificate validation is in offline mode. Set certificate.api-url in config.yml. See " + docsUrl + " for setup guide.");
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
                    caAvailable = true;
                    return;
                }
                plugin.getLogger().warning("[ValleyCert Client] Core certificate expired or invalid, requesting renewal.");
            } catch (IOException e) {
                plugin.getLogger().warning("[ValleyCert Client] Failed to read core certificate: " + e.getMessage());
            }
        }

        for (int attempt = 1; attempt <= MAX_INIT_RETRIES; attempt++) {
            PluginCertificate coreCert = requestCertificateFromCA(
                "valleyauth-core",
                List.of("VLINK", "IDENTITY_LINK", "RANK_SHARE", "MIGRATION_PROVIDER", "MIGRATION_ACCESS", "CERTIFICATE_MANAGEMENT"),
                90
            );

            if (coreCert != null) {
                cachedCertificates.put("core", coreCert);
                saveCertificateToFile("core-cert.json", coreCert);
                caAvailable = true;
                plugin.getLogger().info("[ValleyCert Client] Core certificate obtained: " + coreCert.getCertificateId());
                return;
            }

            if (attempt < MAX_INIT_RETRIES) {
                long delay = RETRY_BASE_DELAY_MS * attempt;
                plugin.getLogger().warning("[ValleyCert Client] CA unreachable (attempt " + attempt + "/" + MAX_INIT_RETRIES + "), retrying in " + delay + "ms...");
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        caAvailable = false;
        plugin.getLogger().severe("[ValleyCert Client] Failed to obtain core certificate after " + MAX_INIT_RETRIES + " attempts.");
        plugin.getLogger().severe("[ValleyCert Client] Running in offline mode — cached certs will be used where available.");
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
                caAvailable = true;
                JsonObject respBody = gson.fromJson(response.body(), JsonObject.class);
                if (respBody.get("success").getAsBoolean()) {
                    JsonObject certJson = respBody.getAsJsonObject("certificate");
                    return parsePluginCertificate(certJson);
                } else {
                    plugin.getLogger().warning("[ValleyCert Client] CA rejected request: " + respBody.get("message").getAsString() + ". See " + docsUrl);
                }
            } else if (response.statusCode() == 400) {
                caAvailable = true;
                JsonObject respBody = gson.fromJson(response.body(), JsonObject.class);
                String msg = respBody.has("message") ? respBody.get("message").getAsString() : "";
                if (msg.contains("already has a valid certificate")) {
                    plugin.getLogger().info("[ValleyCert Client] Plugin already has a cert, fetching existing...");
                    return fetchExistingCertificate(pluginId);
                }
                plugin.getLogger().warning("[ValleyCert Client] CA returned HTTP 400: " + msg + ". See " + docsUrl);
            } else {
                plugin.getLogger().warning("[ValleyCert Client] CA returned HTTP " + response.statusCode() + ". See " + docsUrl);
            }
        } catch (IOException | InterruptedException e) {
            caAvailable = false;
            plugin.getLogger().warning("[ValleyCert Client] CA request failed: " + e.getMessage() + ". See " + docsUrl);
        }
        return null;
    }

    private PluginCertificate fetchExistingCertificate(String pluginId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "/api/certificate/plugin/" + pluginId))
                .GET()
                .timeout(java.time.Duration.ofSeconds(10))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonObject respBody = gson.fromJson(response.body(), JsonObject.class);
                if (respBody.get("success").getAsBoolean()) {
                    JsonObject certJson = respBody.getAsJsonObject("certificate");
                    return parsePluginCertificate(certJson);
                }
            }
            plugin.getLogger().warning("[ValleyCert Client] Failed to fetch existing cert for " + pluginId + ": HTTP " + response.statusCode());
        } catch (IOException | InterruptedException e) {
            plugin.getLogger().warning("[ValleyCert Client] Error fetching existing cert: " + e.getMessage());
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
                plugin.getLogger().warning("[ValleyCert Client] No API configured but certificate enforcement is ON. Denying " + pluginId + ". See " + docsUrl);
                return false;
            }
            return true;
        }

        PluginCertificate cached = cachedCertificates.get(pluginId);
        if (cached != null && isCertificateValid(cached)) {
            if (hasCapability(cached, capability)) {
                if (caAvailable && cached.getCertificateId() != null) {
                    boolean caValid = validateViaCA(cached.getCertificateId());
                    if (!caValid) {
                        plugin.getLogger().warning("[ValleyCert Client] CA says cert " + cached.getCertificateId() + " is invalid — denying " + pluginId);
                        return false;
                    }
                }
                return true;
            }
            return false;
        }

        if (caAvailable) {
            return validateViaAPI(pluginId, capability);
        }

        plugin.getLogger().warning("[ValleyCert Client] No cached cert for " + pluginId + " and CA is offline — denying (offline mode)");
        return false;
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
     * Request a certificate for a plugin — the main entry point for ValleyCert utility.
     *
     * Flow: check cache → if valid, return cached → else request from CA → store locally → return.
     * Returns null on failure (CA unreachable or rejected).
     */
    public PluginCertificate requestCertForPlugin(String pluginId, List<String> capabilities) {
        if (!initialized) {
            plugin.getLogger().warning("[ValleyCert Client] Not initialized — cannot request cert for " + pluginId);
            return null;
        }

        PluginCertificate cached = cachedCertificates.get(pluginId);
        if (cached != null && isCertificateValid(cached) && hasAllCapabilities(cached, capabilities)) {
            plugin.getLogger().info("[ValleyCert Client] Returning cached cert for " + pluginId + ": " + cached.getCertificateId());
            return cached;
        }

        if (apiUrl == null || apiUrl.isBlank()) {
            plugin.getLogger().warning("[ValleyCert Client] No API configured — cannot request cert for " + pluginId + ". See " + docsUrl);
            return null;
        }

        plugin.getLogger().info("[ValleyCert Client] Requesting cert from CA for " + pluginId + "...");
        PluginCertificate cert = requestCertificateFromCA(pluginId, capabilities, 90);

        if (cert != null) {
            cachedCertificates.put(pluginId, cert);
            saveCertificateToFile(pluginId + "-cert.json", cert);
            plugin.getLogger().info("[ValleyCert Client] Certificate obtained and stored for " + pluginId + ": " + cert.getCertificateId());
            return cert;
        }

        plugin.getLogger().warning("[ValleyCert Client] Failed to obtain cert for " + pluginId + " from CA.");
        return null;
    }

    /**
     * Validate a certificate by its ID against the CA's /api/certificate/validate/:id endpoint.
     * Returns true if the CA confirms the certificate is valid.
     */
    public boolean validateViaCA(String certificateId) {
        if (!caAvailable || apiUrl == null || apiUrl.isBlank()) {
            return false;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "/api/certificate/validate/" + certificateId))
                .header("Content-Type", "application/json")
                .GET()
                .timeout(java.time.Duration.ofSeconds(10))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonObject respBody = gson.fromJson(response.body(), JsonObject.class);
                boolean valid = respBody.has("valid") && respBody.get("valid").getAsBoolean();
                plugin.getLogger().info("[ValleyCert Client] CA validation for " + certificateId + ": " + (valid ? "VALID" : "INVALID"));
                return valid;
            } else {
                plugin.getLogger().warning("[ValleyCert Client] CA validate returned HTTP " + response.statusCode() + " for " + certificateId);
                return false;
            }
        } catch (IOException | InterruptedException e) {
            plugin.getLogger().warning("[ValleyCert Client] CA validate request failed: " + e.getMessage());
            caAvailable = false;
            return false;
        }
    }

    /**
     * Returns the core certificate if available, null otherwise.
     */
    public PluginCertificate getCoreCertificate() {
        return cachedCertificates.get("core");
    }

    /**
     * Returns true if the CA was reachable on the last check.
     */
    public boolean isCaAvailable() {
        return caAvailable;
    }

    public PluginCertificate getCachedCertificate(String pluginId) {
        return cachedCertificates.get(pluginId);
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

    private boolean hasAllCapabilities(PluginCertificate cert, List<String> required) {
        if (cert.getCapabilities() == null || required == null) return false;
        return required.stream().allMatch(req ->
            cert.getCapabilities().stream().anyMatch(c -> c.equalsIgnoreCase(req))
        );
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
