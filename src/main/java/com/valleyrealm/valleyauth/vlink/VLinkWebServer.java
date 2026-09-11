package com.valleyrealm.valleyauth.vlink;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.valleyrealm.valleyauth.ValleyAuth;
import com.valleyrealm.valleyauth.config.ConfigManager;
import com.valleyrealm.valleyauth.vlink.VLinkManager.VLinkSession;
import com.valleyrealm.valleyauth.vlink.VLinkManager.VLinkState;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

public class VLinkWebServer {

    private static final int THREAD_POOL_SIZE = 4;
    private static final String CONTENT_TYPE_HTML = "text/html; charset=utf-8";
    private static final String CONTENT_TYPE_JSON = "application/json; charset=utf-8";
    private static final String CONTENT_TYPE_PLAIN = "text/plain; charset=utf-8";

    private final ValleyAuth plugin;
    private final int port;
    private final ConfigManager config;
    private HttpServer server;
    private ExecutorService executor;
    private boolean usingSsl;

    public VLinkWebServer(ValleyAuth plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.port = config.getWebInterfacePort();
    }

    public void start() throws IOException {
        executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        usingSsl = false;

        if (config.isVlinkSslEnabled()) {
            HttpsServer httpsServer = createHttpsServer();
            if (httpsServer != null) {
                server = httpsServer;
                usingSsl = true;
            } else {
                plugin.getLogger().warning("[VLink Web] SSL configuration invalid, falling back to HTTP");
            }
        }

        if (server == null) {
            server = HttpServer.create(new InetSocketAddress(port), 0);
        }

        server.setExecutor(executor);

        server.createContext("/", new RootHandler());
        server.createContext("/migrate/", new MigrateHandler());
        server.createContext("/api/migrate/", new ApiMigrateHandler());
        server.createContext("/api/status/", new ApiStatusHandler());
        server.createContext("/favicon.ico", new FaviconHandler());

        server.start();
        String protocol = usingSsl ? "HTTPS" : "HTTP";
        plugin.getLogger().info("[VLink Web] Server started on " + protocol + " port " + port);
    }

    private HttpsServer createHttpsServer() {
        try {
            String keystorePath = config.getVlinkSslKeystorePath();
            String keystorePassword = config.getVlinkSslKeystorePassword();
            String keystoreType = config.getVlinkSslKeystoreType();

            if (keystorePassword.isEmpty()) {
                plugin.getLogger().warning("[VLink Web] SSL keystore password is empty");
                return null;
            }

            java.nio.file.Path keystoreFile = plugin.getDataFolder().toPath().resolve(keystorePath);
            if (!java.nio.file.Files.exists(keystoreFile)) {
                plugin.getLogger().warning("[VLink Web] Keystore file not found: " + keystoreFile);
                return null;
            }

            KeyStore keyStore = KeyStore.getInstance(keystoreType);
            try (FileInputStream fis = new FileInputStream(keystoreFile.toFile())) {
                keyStore.load(fis, keystorePassword.toCharArray());
            }

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, keystorePassword.toCharArray());

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), null, null);

            HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress(port), 0);
            httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext));

            return httpsServer;
        } catch (Exception e) {
            plugin.getLogger().warning("[VLink Web] Failed to initialize SSL: " + e.getMessage());
            return null;
        }
    }

    /**
     * Returns the base URL for the VLink web interface.
     * Uses HTTPS with domain when SSL is enabled and domain is configured.
     * Uses HTTP with domain when domain is configured but SSL is disabled.
     * Falls back to localhost with port when no domain is configured.
     */
    public String getBaseUrl() {
        String domain = config.getVlinkDomain();
        if (!domain.isEmpty()) {
            String protocol = usingSsl ? "https" : "http";
            if (config.isVlinkShowPort()) {
                return protocol + "://" + domain + ":" + port;
            }
            return protocol + "://" + domain;
        }
        return "http://localhost:" + port;
    }

    public boolean isUsingSsl() {
        return usingSsl;
    }

    public void close() {
        if (server != null) {
            server.stop(2);
            plugin.getLogger().info("[VLink Web] Server stopped.");
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void logRequest(HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String remote = exchange.getRemoteAddress().getAddress().getHostAddress();
        pluginStatic().getLogger().info("[VLink Web] " + method + " " + path + " from " + remote);
    }

    private static ValleyAuth pluginStatic() {
        return ValleyAuth.getInstance();
    }

    private static String escapeHtml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        byte[] buffer = is.readAllBytes();
        return new String(buffer, StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseFormBody(String body) {
        Map<String, String> params = new java.util.LinkedHashMap<>();
        if (body == null || body.isEmpty()) return params;
        String[] pairs = body.split("&");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                params.put(key, value);
            }
        }
        return params;
    }

    private static VLinkSession lookupSession(VLinkManager mgr, String code) {
        return mgr.getSessionByCode(code);
    }

    // --- Handlers ---

    private static class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            logRequest(exchange);
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, CONTENT_TYPE_PLAIN, "Method Not Allowed");
                return;
            }
            sendResponse(exchange, 200, CONTENT_TYPE_HTML, LANDING_PAGE_HTML.replace("__CSS__", CSS));
        }
    }

    private static class MigrateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            logRequest(exchange);
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, CONTENT_TYPE_PLAIN, "Method Not Allowed");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String code = path.substring("/migrate/".length());
            if (code.isEmpty()) {
                sendResponse(exchange, 400, CONTENT_TYPE_PLAIN, "Missing migration code");
                return;
            }
            VLinkManager mgr = pluginStatic().getVlinkManager();
            VLinkSession session = lookupSession(mgr, code);
            if (session == null) {
                sendResponse(exchange, 404, CONTENT_TYPE_HTML, PAGE_NOT_FOUND_HTML.replace("__CSS__", CSS));
                return;
            }
            String html = MIGRATE_PAGE_HTML
                .replace("__CSS__", CSS)
                .replace("__CODE__", escapeHtml(code))
                .replace("__SOURCE_PLAYER__", escapeHtml(session.getSourceIdentity().getCanonicalName()));
            sendResponse(exchange, 200, CONTENT_TYPE_HTML, html);
        }
    }

    private static class ApiMigrateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            logRequest(exchange);
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, CONTENT_TYPE_JSON, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String code = path.substring("/api/migrate/".length());
            VLinkManager mgr = pluginStatic().getVlinkManager();
            VLinkSession session = lookupSession(mgr, code);
            if (session == null) {
                sendResponse(exchange, 404, CONTENT_TYPE_JSON, "{\"error\":\"Invalid or expired code\"}");
                return;
            }
            if (session.getState() != VLinkState.PENDING) {
                sendResponse(exchange, 400, CONTENT_TYPE_JSON, "{\"error\":\"Session is not pending\"}");
                return;
            }
            String body = readBody(exchange);
            Map<String, String> params = parseFormBody(body);

            String serverAddress = params.getOrDefault("serverAddress", "");
            String serverType = params.getOrDefault("serverType", "java");
            String scopeRaw = params.getOrDefault("migrationScope", "");
            List<String> scope = new ArrayList<>();
            if (!scopeRaw.isEmpty()) {
                scope.addAll(Arrays.asList(scopeRaw.split(",")));
            }

            session.setServerAddress(serverAddress);
            session.setServerType(serverType);
            session.setMigrationScope(scope);

            pluginStatic().getLogger().info("[VLink Web] Migration details stored for code " + code
                + ": address=" + serverAddress + ", type=" + serverType + ", scope=" + scope);

            sendResponse(exchange, 200, CONTENT_TYPE_JSON, "{\"ok\":true}");
        }
    }

    private static class ApiStatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            logRequest(exchange);
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, CONTENT_TYPE_JSON, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String code = path.substring("/api/status/".length());
            VLinkManager mgr = pluginStatic().getVlinkManager();
            VLinkSession session = lookupSession(mgr, code);
            if (session == null) {
                sendResponse(exchange, 404, CONTENT_TYPE_JSON, "{\"error\":\"Invalid or expired code\"}");
                return;
            }
            String state = session.getState().name();
            String sourcePlayer = escapeJson(session.getSourceIdentity().getCanonicalName());
            String destType = escapeJson(String.valueOf(session.getDestinationType()));

            String json = "{\"state\":\"" + state + "\",\"sourcePlayer\":\"" + sourcePlayer
                + "\",\"destinationType\":\"" + destType + "\"}";
            sendResponse(exchange, 200, CONTENT_TYPE_JSON, json);
        }
    }

    private static class FaviconHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.sendResponseHeaders(204, -1);
        }
    }

    private static String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    // --- Embedded HTML/CSS/JS ---

    private static final String CSS = """
        :root {
            --bg-primary: #1a1a2e;
            --bg-secondary: #16213e;
            --bg-card: #0f3460;
            --text-primary: #e0e0e0;
            --text-secondary: #a0a0b0;
            --accent-green: #4caf50;
            --accent-green-hover: #66bb6a;
            --accent-blue: #42a5f5;
            --border: #2a2a4a;
            --input-bg: #12122a;
            --danger: #e53935;
        }
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
            background: var(--bg-primary);
            color: var(--text-primary);
            min-height: 100vh;
            display: flex;
            flex-direction: column;
            align-items: center;
        }
        .container {
            width: 100%; max-width: 640px; padding: 2rem 1.5rem;
        }
        header {
            text-align: center; padding: 2.5rem 0 1.5rem;
        }
        header h1 {
            font-size: 1.8rem; color: var(--accent-green);
            letter-spacing: 0.02em;
        }
        header p { color: var(--text-secondary); margin-top: 0.5rem; font-size: 0.95rem; }
        .card {
            background: var(--bg-secondary); border: 1px solid var(--border);
            border-radius: 10px; padding: 1.5rem; margin-bottom: 1.25rem;
        }
        .card h2 {
            font-size: 1.1rem; color: var(--accent-green); margin-bottom: 1rem;
            padding-bottom: 0.5rem; border-bottom: 1px solid var(--border);
        }
        label {
            display: block; font-size: 0.85rem; color: var(--text-secondary);
            margin-bottom: 0.3rem; margin-top: 0.8rem;
        }
        input[type="text"], select {
            width: 100%; padding: 0.6rem 0.8rem; font-size: 0.95rem;
            background: var(--input-bg); border: 1px solid var(--border);
            border-radius: 6px; color: var(--text-primary); outline: none;
            transition: border-color 0.2s;
        }
        input[type="text"]:focus, select:focus { border-color: var(--accent-green); }
        .checkbox-group { margin-top: 0.5rem; }
        .checkbox-group label {
            display: flex; align-items: center; gap: 0.5rem;
            cursor: pointer; font-size: 0.9rem; color: var(--text-primary);
            margin-top: 0.4rem;
        }
        .checkbox-group input[type="checkbox"] {
            accent-color: var(--accent-green); width: 16px; height: 16px;
        }
        .btn {
            display: inline-block; width: 100%; padding: 0.7rem; margin-top: 1.2rem;
            font-size: 1rem; font-weight: 600; border: none; border-radius: 6px;
            cursor: pointer; transition: background 0.2s, transform 0.1s;
            color: #fff;
        }
        .btn:active { transform: scale(0.98); }
        .btn-primary { background: var(--accent-green); }
        .btn-primary:hover { background: var(--accent-green-hover); }
        .btn-primary:disabled { background: #333; cursor: not-allowed; }
        .status-box {
            text-align: center; padding: 1.5rem; font-size: 1.1rem;
        }
        .status-box .icon { font-size: 2.5rem; margin-bottom: 0.8rem; }
        .status-box .label { font-weight: 600; }
        .status-pending { color: var(--accent-blue); }
        .status-completed { color: var(--accent-green); }
        .status-expired { color: var(--danger); }
        .status-failed { color: var(--danger); }
        .verify-command {
            background: var(--input-bg); border: 1px solid var(--border);
            border-radius: 6px; padding: 0.8rem 1rem; margin-top: 1rem;
            font-family: 'Courier New', monospace; font-size: 1rem;
            color: var(--accent-green); text-align: center; user-select: all;
            word-break: break-all;
        }
        .instructions {
            color: var(--text-secondary); font-size: 0.88rem; line-height: 1.6;
            margin-top: 1rem;
        }
        .instructions li { margin-bottom: 0.3rem; }
        .hidden { display: none; }
        .error-msg { color: var(--danger); font-size: 0.85rem; margin-top: 0.5rem; }
        .player-badge {
            display: inline-block; background: var(--bg-card); padding: 0.25rem 0.7rem;
            border-radius: 4px; font-weight: 600; color: var(--accent-green);
            margin-bottom: 0.5rem;
        }
        footer {
            text-align: center; padding: 2rem 0; color: var(--text-secondary);
            font-size: 0.8rem;
        }
        @media (max-width: 480px) {
            .container { padding: 1rem; }
            header h1 { font-size: 1.4rem; }
            .card { padding: 1rem; }
        }
        """;

    private static final String LANDING_PAGE_HTML = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Valley Auth — Migration</title>
            <style>__CSS__</style>
        </head>
        <body>
        <div class="container">
            <header>
                <h1>&#9876; Valley Auth Migration</h1>
                <p>Secure identity migration for Minecraft servers</p>
            </header>
            <div class="card">
                <h2>How It Works</h2>
                <ol class="instructions">
                    <li>In-game, run <strong>/migrate</strong> to start a migration session.</li>
                    <li>You'll receive a short code and a link. Open the link in your browser.</li>
                    <li>Fill in your source server details and migration scope.</li>
                    <li>Click <strong>Generate Verification</strong> — copy the in-game command shown.</li>
                    <li>Run the verification command in-game with <strong>/v link &lt;code&gt;</strong>.</li>
                    <li>Once verified, your migration is queued automatically.</li>
                </ol>
            </div>
            <div class="card">
                <h2>Enter Migration Code</h2>
                <label for="code-input">Your VLink Code</label>
                <input type="text" id="code-input" placeholder="XXXX-XXXX" maxlength="9" autocomplete="off">
                <button class="btn btn-primary" onclick="goToMigration()">Open Migration Form</button>
            </div>
            <footer>Valley Auth &mdash; Secure Server Migration</footer>
        </div>
        <script>
        function goToMigration() {
            var code = document.getElementById('code-input').value.trim().toUpperCase();
            if (code) window.location.href = '/migrate/' + code;
        }
        document.getElementById('code-input').addEventListener('keydown', function(e) {
            if (e.key === 'Enter') goToMigration();
        });
        </script>
        </body></html>
        """;

    private static final String MIGRATE_PAGE_HTML = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Valley Auth — Migrate __CODE__</title>
            <style>__CSS__</style>
        </head>
        <body>
        <div class="container">
            <header>
                <h1>&#9876; Valley Auth Migration</h1>
                <p>Complete the form below to configure your migration</p>
            </header>
            <div class="card" id="player-card">
                <span class="player-badge">Source Player: __SOURCE_PLAYER__</span>
                <span class="player-badge">Code: __CODE__</span>
            </div>

            <div class="card" id="form-card">
                <h2>Migration Configuration</h2>
                <form id="migrate-form" onsubmit="return false;">
                    <label for="server-address">Source Server IP / Address</label>
                    <input type="text" id="server-address" placeholder="e.g. play.example.com:25565" required>

                    <label for="server-type">Source Server Type</label>
                    <select id="server-type">
                        <option value="java">Java Edition</option>
                        <option value="bedrock">Bedrock Edition</option>
                    </select>

                    <label>Migration Scope</label>
                    <div class="checkbox-group">
                        <label><input type="checkbox" name="scope" value="player-data" checked> Player Data (位置, 游戏模式)</label>
                        <label><input type="checkbox" name="scope" value="inventories" checked> Inventories (物品, 装备)</label>
                        <label><input type="checkbox" name="scope" value="advancements" checked> Advancements</label>
                        <label><input type="checkbox" name="scope" value="statistics" checked> Statistics</label>
                    </div>
                    <div id="error-msg" class="error-msg hidden"></div>
                    <button type="button" class="btn btn-primary" id="submit-btn" onclick="submitForm()">Generate Verification</button>
                </form>
            </div>

            <div class="card hidden" id="verify-card">
                <h2>Verify In-Game</h2>
                <div class="status-box status-pending">
                    <div class="icon">&#128276;</div>
                    <div class="label">Run this command in Minecraft:</div>
                    <div class="verify-command" id="verify-cmd"></div>
                    <ol class="instructions" style="text-align:left; margin-top:1rem;">
                        <li>Join your Minecraft server.</li>
                        <li>Open chat and paste the command above.</li>
                        <li>Wait for confirmation — this page updates automatically.</li>
                    </ol>
                </div>
            </div>

            <div class="card hidden" id="status-card">
                <div id="status-content"></div>
            </div>

            <footer>Valley Auth &mdash; Secure Server Migration</footer>
        </div>
        <script>
        var CODE = '__CODE__';
        var pollTimer = null;

        function submitForm() {
            var address = document.getElementById('server-address').value.trim();
            if (!address) { showError('Please enter a server address.'); return; }
            var type = document.getElementById('server-type').value;
            var checks = document.querySelectorAll('input[name="scope"]:checked');
            var scope = [];
            checks.forEach(function(c) { scope.push(c.value); });
            if (scope.length === 0) { showError('Select at least one migration scope.'); return; }

            var body = 'serverAddress=' + encodeURIComponent(address)
                + '&serverType=' + encodeURIComponent(type)
                + '&migrationScope=' + encodeURIComponent(scope.join(','));

            var xhr = new XMLHttpRequest();
            xhr.open('POST', '/api/migrate/' + CODE, true);
            xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
            xhr.onload = function() {
                if (xhr.status === 200) {
                    hideError();
                    document.getElementById('form-card').classList.add('hidden');
                    document.getElementById('verify-card').classList.remove('hidden');
                    document.getElementById('verify-cmd').textContent = '/v link ' + CODE;
                    startPolling();
                } else {
                    var resp = {};
                    try { resp = JSON.parse(xhr.responseText); } catch(e) {}
                    showError(resp.error || 'Failed to store migration details.');
                }
            };
            xhr.onerror = function() { showError('Network error. Try again.'); };
            xhr.send(body);
        }

        function startPolling() {
            pollTimer = setInterval(function() {
                var xhr = new XMLHttpRequest();
                xhr.open('GET', '/api/status/' + CODE, true);
                xhr.onload = function() {
                    if (xhr.status === 200) {
                        var data = {};
                        try { data = JSON.parse(xhr.responseText); } catch(e) { return; }
                        updateStatus(data.state);
                        if (data.state === 'COMPLETED' || data.state === 'EXPIRED' || data.state === 'FAILED') {
                            clearInterval(pollTimer);
                        }
                    }
                };
                xhr.send();
            }, 2000);
        }

        function updateStatus(state) {
            var card = document.getElementById('status-card');
            var content = document.getElementById('status-content');
            card.classList.remove('hidden');
            if (state === 'COMPLETED') {
                content.innerHTML = '<div class="status-box status-completed">'
                    + '<div class="icon">&#10004;</div>'
                    + '<div class="label">Migration Queued!</div>'
                    + '<p style="margin-top:0.5rem; color:var(--text-secondary);">'
                    + 'Check <strong>/migrate status</strong> in-game for progress.</p></div>';
            } else if (state === 'EXPIRED') {
                content.innerHTML = '<div class="status-box status-expired">'
                    + '<div class="icon">&#9200;</div>'
                    + '<div class="label">Session Expired</div>'
                    + '<p style="margin-top:0.5rem; color:var(--text-secondary);">'
                    + 'This code has expired. Run <strong>/migrate</strong> in-game to get a new one.</p></div>';
            } else if (state === 'FAILED') {
                content.innerHTML = '<div class="status-box status-failed">'
                    + '<div class="icon">&#10060;</div>'
                    + '<div class="label">Verification Failed</div>'
                    + '<p style="margin-top:0.5rem; color:var(--text-secondary);">Please try again.</p></div>';
            } else {
                content.innerHTML = '<div class="status-box status-pending">'
                    + '<div class="icon">&#8987;</div>'
                    + '<div class="label">Waiting for in-game verification...</div>'
                    + '<p style="margin-top:0.5rem; color:var(--text-secondary);">'
                    + 'Run <strong>/v link ' + CODE + '</strong> in Minecraft.</p></div>';
            }
        }

        function showError(msg) {
            var el = document.getElementById('error-msg');
            el.textContent = msg;
            el.classList.remove('hidden');
        }
        function hideError() {
            document.getElementById('error-msg').classList.add('hidden');
        }

        startPolling();
        </script>
        </body></html>
        """;

    private static final String PAGE_NOT_FOUND_HTML = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Valley Auth — Not Found</title>
            <style>__CSS__</style>
        </head>
        <body>
        <div class="container">
            <header>
                <h1>&#9876; Valley Auth Migration</h1>
            </header>
            <div class="card">
                <div class="status-box status-expired">
                    <div class="icon">&#128269;</div>
                    <div class="label">Session Not Found</div>
                    <p style="margin-top:0.8rem; color:var(--text-secondary);">
                        This migration code is invalid or has expired.<br>
                        Run <strong>/migrate</strong> in-game to generate a new code.
                    </p>
                    <a href="/" class="btn btn-primary" style="text-decoration:none; margin-top:1rem; display:inline-block; width:auto; padding:0.6rem 2rem;">Back to Home</a>
                </div>
            </div>
        </div>
        </body></html>
        """;
}
