# ValleyAuth

![GitHub Tag](https://img.shields.io/github/v/tag/SabeeirSharrma/ValleyAuth)
![License](https://img.shields.io/badge/license-MIT-green)
![API](https://img.shields.io/badge/Paper-1.21%2B-orange)

Offline-mode authentication, identity management, and player-data migration for Paper Minecraft servers.

ValleyAuth solves a specific problem: running a server in offline mode (cracked) while giving players a path to premium accounts, Bedrock cross-play, and full data portability. It handles the authentication, the identity model, and the messy work of moving player data between UUIDs.

---

## Table of Contents

- [Features](#features)
- [Architecture](#architecture)
- [Requirements](#requirements)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Certificate Setup](#certificate-setup)
- [Authentication System](#authentication-system)
- [Migration System](#migration-system)
- [LuckPerms Integration](#luckperms-integration)
- [Geyser/Floodgate Support](#geyserfloodgate-support)
- [Commands Reference](#commands-reference)
- [Configuration Reference](#configuration-reference)
- [Reverse Proxy Setup](#reverse-proxy-setup)
- [Security](#security)
- [Troubleshooting](#troubleshooting)
- [Building from Source](#building-from-source)
- [License](#license)

---

## Features

| Feature | Description |
|---|---|
| **Offline Authentication** | Password-based register/login for cracked servers. PBKDF2 hashing with 100k iterations. |
| **Identity Model** | Three identity types (Premium, Bedrock, Offline) with configurable name prefixes. Prevents impersonation between types. |
| **VLink Web Interface** | Built-in HTTP/HTTPS web server for browser-based migration configuration. Dark-themed, mobile-responsive. |
| **Certificate Authority** | Integrates with ValleyCert for plugin certificate validation. Capability-gated operations. |
| **Player Data Migration** | Moves player files (JSON, YAML, properties, text) between UUIDs. Queue system with concurrency control. |
| **LuckPerms Migration** | Copies all permission nodes, groups, contexts, and expiry data between UUIDs. |
| **Bedrock Support** | Floodgate adapter for Bedrock Edition players with automatic identity prefixing. |
| **Unsafe Addon Detection** | Scans for potentially dangerous server plugins at startup. |
| **SSL Support** | Built-in TLS termination for the web server using JKS/PKCS12 keystores. |

---

## Architecture

ValleyAuth is built as a set of cooperating systems inside a single plugin JAR:

```
ValleyAuth Core (ValleyAuth.java)
  |
  +-- ConfigManager          Loads and provides config.yml values
  +-- IdentityManager        Manages Premium/Bedrock/Offline identities, caches them, indexes plugin storage for migration
  +-- AuthenticationManager  Handles register/login for offline players, PBKDF2 hashing
  +-- StorageManager         Persistent storage for passwords and offline UUID mappings
  |
  +-- ValleyCertClient       Requests and caches certificates from ValleyCertAPI CA
  +-- CertificateValidator   Validates certificates, checks capabilities, decrypts revocation timestamps
  |
  +-- VLinkManager           Generates short codes, manages sessions, verifies in-game links
  +-- VLinkWebServer         Embedded HTTP(S) web server with migration UI and API
  |
  +-- MigrationManager       Queue system, concurrency control, job lifecycle
  +-- FileMigrationEngine    Opens indexed files, replaces old UUID with new UUID, closes files
  |
  +-- LuckPermsAdapter       Detects LuckPerms, copies permissions between UUIDs (soft dependency)
  +-- FloodgateAdapter       Detects Floodgate, enables Bedrock identity type (soft dependency)
  +-- UnsafeAddonManager     Scans plugins directory for known risky addons
```

The startup order matters:

1. **Phase 1**: Core initialization (config, storage, identity manager). No certificate required.
2. **Phase 2**: LuckPerms detection, ValleyCert client initialization. The client requests a certificate from the CA or loads a cached one.
3. **Phase 3**: Authentication, VLink, migration system. These depend on the identity manager being ready.
4. **Phase 4**: Floodgate adapter (Bedrock support).
5. **Phase 5**: Security systems (unsafe addon detection), command registration, listener registration.

---

## Requirements

| Dependency | Version | Required | Purpose |
|---|---|---|---|
| Paper | 1.21+ | Yes | Server platform |
| JDK | 21+ | Yes | Java runtime |
| LuckPerms | 5.x | Optional | Permission migration during player data transfer |
| Floodgate | 2.2.x | Optional | Bedrock Edition player support |

Geyser is not a direct dependency, but Floodgate (which ships with Geyser) is needed for Bedrock support.

---

## Installation

### Step 1: Download

Grab the latest `ValleyAuth-*.jar` from [GitHub Releases](https://github.com/SabeeirSharrma/ValleyAuth/releases) or Modrinth.

### Step 2: Place the JAR

Copy it into your server's `plugins/` directory.

### Step 3: First start

Start or restart your server. ValleyAuth will:

1. Create `plugins/ValleyAuth/config.yml` with defaults
2. Request a certificate from the ValleyCert API (logs will show success or failure)
3. Cache the certificate at `plugins/ValleyAuth/data/core-cert.json`
4. Start the VLink web server on port 8080

### Step 4: Configure

Edit `plugins/ValleyAuth/config.yml`. At minimum, set your domain:

```yaml
vlink:
  domain: "auth.example.com"
```

### Step 5: Restart

Restart the server to pick up the new configuration.

---

## Quick Start

### 1. Set your domain

```yaml
vlink:
  domain: "auth.example.com"
  show-port: false  # Set true if no reverse proxy
```

### 2. Open the web interface port

The VLink web server listens on port 8080 by default. Open it in your firewall:

```bash
# UFW
sudo ufw allow 8080/tcp

# firewalld
sudo firewall-cmd --add-port=8080/tcp --permanent
sudo firewall-cmd --reload

# iptables
sudo iptables -A INPUT -p tcp --dport 8080 -j ACCEPT
```

If you use a reverse proxy, open 443/80 instead and point it at `localhost:8080`.

### 3. Start the server

Check logs for certificate status. Then verify in-game:

```
/valleyauth status
```

### 4. Register and login

Players connect and run:

```
/register MySecurePassword123
```

On subsequent joins:

```
/login MySecurePassword123
```

---

## Certificate Setup

ValleyAuth uses a certificate authority (ValleyCert) to gate protected operations. Plugins that want to use VLink, identity linking, or migration must present a valid certificate with the right capabilities.

### How it works

1. On first startup, ValleyAuth requests a core certificate from the configured CA
2. The CA issues a certificate with capabilities: `VLINK`, `IDENTITY_LINK`, `RANK_SHARE`, `MIGRATION_PROVIDER`, `MIGRATION_ACCESS`, `CERTIFICATE_MANAGEMENT`
3. The certificate is valid for 90 days and cached locally at `plugins/ValleyAuth/data/core-cert.json`
4. On subsequent startups, the cached certificate is loaded. If expired, a renewal is requested
5. If the CA is reachable, certificates are validated in real time against the CA's `/api/certificate/validate/:id` endpoint

### Public CA (testing)

The default CA is at `https://cert.valleyrealm.qd.je`. This works out of the box for development and testing. Leave the default in config:

```yaml
certificate:
  api-url: "https://cert.valleyrealm.qd.je"
```

### Self-hosted CA (production)

For production, host your own ValleyCertAPI instance. Benefits:

- Full control over keys and certificate issuance
- No dependency on an external service
- Complete revocation control
- Custom capability assignments

Set your own CA URL:

```yaml
certificate:
  api-url: "https://cert.your-domain.com"
```

See the [ValleyCertAPI repository](https://github.com/SabeeirSharrma/ValleyCertAPI) for deployment instructions.

### First startup behavior

When the plugin starts for the first time:

1. The client checks for an existing `core-cert.json` file
2. If none exists (or it is expired), the client requests a new certificate from the CA
3. The request includes the plugin ID `valleyauth-core` and the required capabilities
4. Up to 3 retry attempts are made with exponential backoff (2s, 4s, 6s)
5. If all retries fail, ValleyAuth runs in offline mode using cached certificates only
6. The CA availability status is logged and visible via `/valleyauth status`

### Checking certificate status

Run in-game:

```
/valleyauth status
```

This shows:

- Core certificate ID
- Certificate status (Valid, Expired, Revoked)
- CA status (Online, Offline)
- API URL

### Certificate capabilities

| Capability | Purpose |
|---|---|
| `VLINK` | Create and manage VLink sessions for identity linking |
| `IDENTITY_LINK` | Associate identities across types |
| `RANK_SHARE` | Copy rank/permission data between identities |
| `MIGRATION_PROVIDER` | Register as a migration data provider |
| `MIGRATION_ACCESS` | Access migration job data and state |
| `CERTIFICATE_MANAGEMENT` | Issue and manage certificates for other plugins |

---

## Authentication System

ValleyAuth handles three authentication methods based on the player's identity type:

| Identity Type | Authentication Method |
|---|---|
| Premium (Java) | Automatic (online-mode, handled by Mojang) |
| Bedrock | Automatic (Floodgate handles verification) |
| Offline (Java) | Password-based (/register, /login) |

### Offline auth flow

1. Player joins the server
2. ValleyAuth checks if the username is protected by a Premium identity. If so, the offline player is rejected with a message to connect with their Premium account
3. If the player has a stored password, they must run `/login <password>` within the configured timeout (default: 60 seconds)
4. If no password exists, they must run `/register <password>` within the same timeout
5. Until authenticated, the player is frozen (cannot move, interact, or chat beyond auth commands)
6. On successful auth, the player is released and can play normally
7. On disconnect, the auth state is cleared. The player must re-authenticate on next join

### Password hashing

Passwords are hashed using PBKDF2WithHmacSHA256:

- **Iterations**: 100,000
- **Key length**: 256 bits
- **Salt**: 16 bytes, randomly generated per password
- **Storage format**: `<salt_hex>:<hash_hex>`

This is a standard, well-reviewed hashing scheme. The high iteration count makes brute-force attacks impractical.

### Auth timeout

The `authentication.login-timeout-seconds` setting (default: 60) controls how long a player has to authenticate before being kicked. This prevents ghost players from occupying server slots.

### Password requirements

- Minimum length: 6 characters (configurable via `authentication.min-password-length`)
- Maximum length: 32 characters (configurable via `authentication.max-password-length`)

---

## Migration System

ValleyAuth migrates offline players to Premium (or Bedrock) identity types. This moves their entire player data: position, inventory, advancements, statistics, and permissions.

### Migration flow

#### Step 1: Start migration

The player runs `/migrate` in-game. This:

- Creates a VLink session with a short verification code (format: `XXXX-XXXX`)
- Generates a URL to the web interface
- Displays both to the player

#### Step 2: Configure on the web

The player opens the URL in a browser and fills in:

- **Source server IP/address**: Where their offline data lives
- **Server type**: Java Edition or Bedrock Edition
- **Migration scope**: Checkboxes for player data, inventories, advancements, statistics

After submitting, the web page shows the `/v link <code>` command to run in-game.

#### Step 3: Verify in-game

The player runs the displayed `/v link <code>` command in Minecraft chat. This:

- Validates the code is not expired or already used
- Associates the player's Premium (or Bedrock) UUID with the session
- Queues the migration job

#### Step 4: Migration executes

The migration job runs in the background:

1. The `FileMigrationEngine` opens each indexed file from the `MigrationIndex`
2. It searches for the exact old UUID string
3. Replaces it with the new UUID
4. Closes the file (no handles are held between startup and migration)
5. LuckPerms permissions are copied if LuckPerms is installed
6. Registered migration providers are invoked for additional data

#### Step 5: Check progress

The player runs `/migrate status` to see job state, progress percentage, and queue position.

### What gets migrated

| Data Type | Format | Migrated |
|---|---|---|
| Player data (position, gamemode) | JSON, YAML | Yes |
| Inventories (items, equipment) | JSON, YAML | Yes |
| Advancements | JSON, YAML | Yes |
| Statistics | JSON, YAML, properties | Yes |
| LuckPerms permissions | LuckPerms API | Yes (if installed) |

### Supported file formats

The `FileMigrationEngine` handles:

- `.json` (parsed and rewritten)
- `.yml`, `.yaml` (text replacement)
- `.properties` (text replacement)
- `.txt`, `.conf`, `.cfg` (text replacement)

Database files (`.db`, `.sqlite`, `.sqlite3`, `.mdb`) are excluded. The engine never opens them.

### Migration types

| Migration Type | Source | Destination | Supported |
|---|---|---|---|
| `OFFLINE_TO_PREMIUM` | Offline UUID | Mojang UUID | Yes |
| `OFFLINE_TO_BEDROCK` | Offline UUID | Floodgate UUID | Yes (requires Floodgate) |

### Concurrency

The migration queue system supports:

- **Max concurrent migrations**: Configurable via `migration.max-concurrent` (default: 5)
- **Queue processing**: Checks every second for available slots
- **Identity locking**: Prevents concurrent modifications to the same identity (deadlock-safe ordering)
- **Player presence not required**: Once queued, the player does not need to stay online

### VLink session details

| Property | Value |
|---|---|
| Code format | `XXXX-XXXX` (8 characters) |
| Code character set | `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (no confusing chars like 0/O, 1/I/L) |
| Session expiry | 30 minutes (configurable via `vlink.expiry-minutes`) |
| Used code retention | 24 hours (prevents replay) |
| Cleanup interval | Every minute |

---

## LuckPerms Integration

ValleyAuth automatically detects LuckPerms at startup. When present, the `LuckPermsAdapter` hooks into the LuckPerms API to copy permissions during migration.

### What gets copied

- All permission nodes (with their true/false values)
- Group assignments
- Context key-value pairs
- Time-based expiry data

### How it works

1. On plugin startup, ValleyAuth checks if LuckPerms is installed
2. If found, it obtains the `LuckPerms` API instance via `LuckPermsProvider.get()`
3. During migration, after file migration completes, the adapter:
   - Loads the old user's LuckPerms data by UUID
   - Loads the new user's LuckPerms data by UUID
   - Copies every permission node from old to new, preserving contexts and expiry
   - Saves the updated new user

### Graceful fallback

If LuckPerms is not installed:

- The adapter logs "LuckPerms not found - permission migration disabled"
- No errors occur
- File migration proceeds normally
- The `softdepend` declaration in `plugin.yml` ensures ValleyAuth loads without LuckPerms

### No configuration needed

The adapter hooks in automatically. Server admins do not need to configure anything.

---

## Geyser/Floodgate Support

ValleyAuth integrates with Floodgate (part of the Geyser project) to support Bedrock Edition players.

### Geyser-first check

When a Bedrock player connects through Geyser/Floodgate, Floodgate assigns them a Floodgate UUID. ValleyAuth detects this via the `FloodgateAdapter` and treats the player as a Bedrock identity.

### Bedrock identity prefix

Bedrock players get a configurable prefix (default: `.`) applied to their username. This creates a distinct canonical name that prevents collisions with Java players.

For example, a Bedrock player named `Steve` would have the canonical name `.Steve`, while a Java Premium player named `Steve` would have `Steve` (no prefix).

### Configuration

```yaml
identity:
  bedrock-prefix: "."  # Prefix for Bedrock players
```

### Soft dependency

Floodgate is listed as a `softdepend` in `plugin.yml`. ValleyAuth works without it. The Bedrock identity type is only available when Floodgate is detected and operational.

---

## Commands Reference

### Player Commands

| Command | Permission | Description | Example |
|---|---|---|---|
| `/register <password>` | `valleyauth.register` | Register a new offline account | `/register MyPass123` |
| `/login <password>` | `valleyauth.login` | Login to your offline account | `/login MyPass123` |
| `/migrate` | `valleyauth.migrate` | Start an offline-to-premium migration session | `/migrate` |
| `/migrate status` | `valleyauth.migrate` | Check your migration job progress | `/migrate status` |
| `/migrate help` | `valleyauth.migrate` | Show migration help | `/migrate help` |
| `/v link <code>` | `valleyauth.vlink` | Verify a VLink migration code in-game | `/v link ABCD-EFGH` |

All player commands default to `true` (allowed for all players).

### Admin Commands

| Command | Permission | Description | Example |
|---|---|---|---|
| `/valleyauth status` | `valleyauth.admin` | View plugin version, certificate status, storage scan | `/valleyauth status` |
| `/valleyauth reload` | `valleyauth.admin` | Reload configuration from disk | `/valleyauth reload` |
| `/valleyauth help` | `valleyauth.admin` | Show admin help | `/valleyauth help` |

Admin commands require operator status by default.

### All Permissions

| Permission | Default | Description |
|---|---|---|
| `valleyauth.register` | `true` | Allows `/register` |
| `valleyauth.login` | `true` | Allows `/login` |
| `valleyauth.migrate` | `true` | Allows `/migrate` |
| `valleyauth.vlink` | `true` | Allows `/v link` |
| `valleyauth.admin` | `op` | Allows `/valleyauth` admin commands |
| `valleyauth.migration.status` | `true` | Allows checking migration status |

---

## Configuration Reference

Full reference for `plugins/ValleyAuth/config.yml`:

### Identity Settings

```yaml
identity:
  # Prefix for Java Edition premium (Mojang-authenticated) players.
  # Empty by default. Example: "P-" would make "Steve" display as "P-Steve".
  premium-prefix: ""

  # Prefix for Bedrock Edition players (via Floodgate).
  # Default: "." to visually distinguish from Java players.
  bedrock-prefix: "."

  # Prefix for offline/cracked players.
  # Default: "-" to clearly mark offline accounts.
  offline-prefix: "-"
```

These prefixes are used in the canonical identity name. They prevent name collisions between identity types and make it visually clear which authentication method a player uses.

### Migration Settings

```yaml
migration:
  # Maximum number of migrations running simultaneously.
  # Additional jobs queue and process in order.
  # Lower values reduce server load during migrations.
  max-concurrent: 5

  web-interface:
    # Enable the built-in VLink web server.
    # Set to false if you only use in-game migration or have an external interface.
    enabled: true

    # Port for the web server.
    # Open this port in your firewall, or use a reverse proxy.
    port: 8080
```

### VLink Settings

```yaml
vlink:
  # Public domain for migration URLs.
  # Example: "auth.example.com"
  # Leave empty to use localhost with port.
  domain: ""

  # Include the port in generated URLs.
  # Set to false (default) when using a reverse proxy.
  # Set to true when players connect directly to IP:port.
  # Example false: https://auth.example.com/migrate/ABCD-EFGH
  # Example true:  https://auth.example.com:25565/migrate/ABCD-EFGH
  show-port: false

  # Length of VLink verification codes (characters, excluding dash).
  # Default: 8, which generates codes like "ABCD-EFGH".
  code-length: 8

  # Minutes before a VLink session expires.
  # Shorter values improve security but give players less time.
  expiry-minutes: 30

  ssl:
    # Enable TLS on the web server.
    # If false, the server uses plain HTTP.
    # Recommended: use a reverse proxy with TLS instead.
    enabled: false

    # Path to the keystore file, relative to the plugin data folder.
    # Example: "keystore.jks" looks in plugins/ValleyAuth/keystore.jks
    keystore-path: "keystore.jks"

    # Password for the keystore.
    keystore-password: ""

    # Keystore format. Common values: "JKS", "PKCS12".
    keystore-type: "JKS"
```

### Authentication Settings

```yaml
authentication:
  # Seconds before an unauthenticated player is kicked.
  # Players must /register or /login within this window.
  login-timeout-seconds: 60

  # Maximum password length (characters).
  max-password-length: 32

  # Minimum password length (characters).
  min-password-length: 6
```

### Security Settings

```yaml
security:
  # Require valid ValleyCert certificates for plugin capabilities.
  # If true, plugins without valid certificates are denied access to
  # protected operations (VLink, migration, identity linking).
  # Set to false only for development/testing.
  enforce-certificate-authorization: true

  # Log authentication events and certificate operations.
  # Useful for auditing and debugging.
  log-security-events: true
```

### Certificate Settings

```yaml
certificate:
  # ValleyCert API URL.
  # Default is the public CA for testing.
  # For production, host your own and set the URL here.
  api-url: "https://cert.valleyrealm.qd.je"

  # Documentation URL shown in security warnings and log messages.
  docs-url: "https://docs.valleyrealm.qd.je/certificates"
```

### Logging

```yaml
logging:
  # Enable verbose/debug logging.
  # Produces more detailed log output for troubleshooting.
  verbose: false
```

---

## Reverse Proxy Setup

If you run a reverse proxy (Caddy, Nginx, Traefik) in front of your server, point it at the VLink web interface port (default: 8080).

### Caddy

```
auth.example.com {
    reverse_proxy localhost:8080
}
```

Caddy automatically provisions HTTPS via Let's Encrypt. No extra configuration needed.

### Nginx

```nginx
server {
    listen 443 ssl;
    server_name auth.example.com;

    ssl_certificate     /etc/letsencrypt/live/auth.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/auth.example.com/privkey.pem;

    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

### The show-port setting

The `vlink.show-port` setting controls whether the port appears in generated URLs:

| Setting | When to use | URL example |
|---|---|---|
| `false` (default) | Reverse proxy handles TLS and port routing | `https://auth.example.com/migrate/ABCD-EFGH` |
| `true` | Players connect directly to server IP:port | `https://auth.example.com:25565/migrate/ABCD-EFGH` |

---

## Security

### Certificate validation

ValleyAuth validates certificates through multiple layers:

1. **Capability check**: Does the certificate have the required capability?
2. **Expiry check**: Has the certificate expired?
3. **Revocation check**: Is the certificate revoked?
4. **CA validation**: If the CA is online, does it confirm the certificate is valid?

### Encrypted revocation timestamps

Certificates can include an encrypted revocation timestamp (AES-GCM). When present:

1. The timestamp is decrypted using a shared revocation key
2. The elapsed time since revocation is compared against the certificate's allowed lifetime
3. If the elapsed time exceeds the lifetime, the certificate is treated as revoked
4. On any decryption or parse error, the system fails closed (denies access)

### Offline mode fallback

When the CA is unreachable:

- Cached certificates are used where available
- If no cached certificate exists for a plugin, access is denied
- If `security.enforce-certificate-authorization` is `false`, all plugins are allowed (development mode)

### Username protection

When a Premium player has connected, their username is protected. An offline player trying to use the same username is rejected with a message to connect with their Premium account.

### Identity isolation

Each identity type has a distinct canonical name (via prefixes). This prevents:

- An offline player from impersonating a Premium player
- A Bedrock player from colliding with a Java player
- Cross-type identity confusion

---

## Troubleshooting

### Certificate shows "Offline" or "None"

**Problem**: `/valleyauth status` shows no certificate or CA offline.

**Cause**: The ValleyCert API is unreachable, or the URL is misconfigured.

**Fix**:
1. Check `certificate.api-url` in config.yml
2. Verify network connectivity: `curl https://cert.valleyrealm.qd.je/api/certificate/validate/test`
3. Check server logs for `[ValleyCert Client]` messages
4. If the CA is down, ValleyAuth runs in offline mode with cached certs

### Web interface not accessible

**Problem**: The migration URL returns "connection refused".

**Fix**:
1. Check that `migration.web-interface.enabled: true` in config.yml
2. Verify the port is open: `sudo ufw allow 8080/tcp`
3. Check logs for `[VLink Web] Server started on HTTP port 8080`
4. If using a reverse proxy, verify it points to `localhost:8080`

### Players kicked for not logging in

**Problem**: Players are kicked before they can type /login.

**Fix**:
1. Increase `authentication.login-timeout-seconds` in config.yml
2. Default is 60 seconds, try 120 or 180
3. Run `/valleyauth reload` to apply changes without restart

### Migration stuck in queue

**Problem**: `/migrate status` shows "QUEUED" but progress does not advance.

**Fix**:
1. Check `migration.max-concurrent` in config.yml
2. Run `/valleyauth status` to see how many jobs are running
3. Wait for current jobs to finish, or increase `max-concurrent`
4. Check logs for `[Migration]` messages

### Password verification fails

**Problem**: A player is sure their password is correct but `/login` rejects it.

**Possible causes**:
1. Password hashes are tied to the player's offline UUID. If the UUID changed (e.g., server migration), hashes will not match
2. The password may have been entered with different casing
3. The stored hash may be corrupted

**Fix**: The player needs to `/register` again with a new password if the UUID changed.

### SSL keystore error

**Problem**: Logs show `[VLink Web] SSL configuration invalid, falling back to HTTP`.

**Fix**:
1. Verify the keystore file exists at `plugins/ValleyAuth/<keystore-path>`
2. Check that `vlink.ssl.keystore-password` matches the keystore
3. Verify `vlink.ssl.keystore-type` matches the file format (JKS, PKCS12)
4. Generate a keystore: `keytool -genkeypair -alias server -keyalg RSA -keysize 2048 -keystore keystore.jks`

---

## Building from Source

Requires JDK 21+.

```bash
# Clone the repository
git clone https://github.com/SabeeirSharrma/ValleyAuth.git
cd ValleyAuth

# Build the plugin
./gradlew build

# Output JAR location:
# build/libs/ValleyAuth-<version>.jar
```

### Available Gradle tasks

| Task | Description |
|---|---|
| `./gradlew build` | Compile, test, and package the plugin |
| `./gradlew clean` | Remove all build artifacts |
| `./gradlew runServer` | Start a test Paper server with the plugin loaded |

### Dependencies

| Dependency | Scope | Version |
|---|---|---|
| Paper API | compileOnly | Configured via `paperVersion` property |
| Floodgate API | compileOnly | 2.2.3-SNAPSHOT |
| LuckPerms API | compileOnly | 5.4 |
| Gson | implementation | 2.10.1 |

---

## License

MIT License. See [LICENSE](LICENSE) for details.
