# ValleyAuth Documentation

<!-- BADGES: Replace these placeholders with actual badge URLs when publishing -->

`[VERSION]` `[PAPER]` `[JAVA]` `[STATUS]`

---

## Table of Contents

1. [What is ValleyAuth](#what-is-valleyauth)
2. [Requirements](#requirements)
3. [Installation](#installation)
4. [Quick Start](#quick-start)
5. [How Authentication Works](#how-authentication-works)
6. [Identity System](#identity-system)
7. [VLink: Web-Based Migration](#vlink-web-based-migration)
8. [Migration System](#migration-system)
9. [Certificate System](#certificate-system)
10. [Integration Support](#integration-support)
11. [Configuration Reference](#configuration-reference)
12. [Command Reference](#command-reference)
13. [Permissions Reference](#permissions-reference)
14. [Architecture](#architecture)
15. [Troubleshooting](#troubleshooting)
16. [FAQ](#faq)
17. [Changelog](#changelog)

---

## What is ValleyAuth

ValleyAuth is a Paper plugin for Minecraft servers that run in offline mode. It handles player authentication, manages multiple identity types (premium, bedrock, and offline), and provides tools for migrating player data between accounts.

Most Minecraft servers that enable `online-mode=false` lose all player authentication. Anyone can log in as anyone else. ValleyAuth fixes this by adding its own registration and login system on top of the offline-mode server, while still supporting premium (Java), bedrock (via Floodgate/Geyser), and generated offline identities.

The plugin also ships a built-in web interface called VLink. Players can open a URL, enter a short code, and initiate a migration that moves their player data (worlds, inventories, plugin storage) from one UUID to another. This is useful when a player switches from an offline account to a premium account, or from an offline account to a bedrock account.

**Current version:** v0.2.0-alpha

---

## Requirements

| Component | Minimum Version | Notes |
|-----------|----------------|-------|
| Minecraft Server | Paper 1.21+ | Spigot and other forks are not supported |
| Java | 21+ | Compiles with JDK 21, tested on Java 26 |
| ValleyCertCA | Any | The certificate authority must be running and reachable at the configured URL |

**Optional (soft dependencies):**

| Plugin | Purpose |
|--------|---------|
| Floodgate | Bedrock player detection and UUID resolution |
| Geyser | Required by Floodgate for Bedrock support |
| LuckPerms | Permission and group migration during UUID changes |

If Floodgate and Geyser are not installed, bedrock players are treated as offline players. If LuckPerms is not installed, permission data is not migrated.

---

## Installation

### Step 1: Download

Download the latest `ValleyAuth.jar` from the releases page. Make sure the file name is exactly `ValleyAuth.jar`.

### Step 2: Stop the Server

Stop your Paper server completely. Do not just reload the plugins.

```bash
# If using a control panel, use the stop button.
# From terminal:
stop
```

### Step 3: Place the JAR

Copy `ValleyAuth.jar` into your server's `plugins/` folder:

```
server/
  plugins/
    ValleyAuth.jar
```

### Step 4: Optional Dependencies

If you want bedrock player support, install Geyser and Floodgate into the same `plugins/` folder. If you want permission migration, install LuckPerms.

### Step 5: First Start

Start the server. ValleyAuth will create its data folders and configuration on first boot:

```
plugins/
  ValleyAuth/
    config.yml
    data/
      offline-uuids.json
      passwords.json
      unsafe-plugins.json
    migrations/
    vlink/
    certs/
```

### Step 6: Edit Configuration

Open `plugins/ValleyAuth/config.yml` in a text editor. At minimum, review these settings:

- `vlink.domain` — Set this to your server's domain if you want the VLink web interface to show a shareable URL.
- `migration.web-interface.enabled` — Set to `false` if you don't want the built-in web server.
- `migration.web-interface.port` — Change if port 8080 is already in use.
- `security.enforce-certificate-authorization` — Set to `false` if you don't use the ValleyCert certificate system.

### Step 7: Reload

Either restart the server or run:

```
/valleyauth reload
```

---

## Quick Start

After installation, here's what a typical session looks like:

1. A new player joins the server for the first time.
2. They see a message telling them to register with `/register <password>`.
3. They run `/register mypassword123` and the password is stored (hashed, never in plain text).
4. Every time they rejoin, they run `/login mypassword123` within 60 seconds or they get kicked.
5. If the player has a premium Minecraft account and wants to migrate their offline data, they run `/migrate`.
6. They get a code and a URL. They open the URL in a browser, enter the code, and follow the prompts.
7. After confirming in-game with `/v link <code>`, their data is moved to the premium UUID.

---

## How Authentication Works

ValleyAuth only requires manual authentication for offline-mode players. Premium and bedrock players are authenticated automatically by the server or Floodgate.

### Authentication Flow by Identity Type

| Identity Type | How Authentication Works |
|---------------|------------------------|
| Premium | Automatic. The server verifies the Mojang session. No player action needed. |
| Bedrock | Automatic. Floodgate handles authentication. No player action needed. |
| Offline | Manual. Player must `/register` then `/login` with a password. |

### Offline Player Join Sequence

When an offline player joins:

1. ValleyAuth checks if the username is protected by a premium identity (impersonation guard).
2. If the player already has a stored password, they see: "Please log in with `/login <password>`".
3. If no password exists, they see: "Please register with `/register <password>`".
4. A 60-second countdown starts. If the player doesn't authenticate in time, they are kicked.
5. While waiting to authenticate, the player cannot chat or move more than half a block.

### Password Hashing

Passwords are stored using PBKDF2, a slow-by-design hashing algorithm that resists brute-force attacks.

| Parameter | Value |
|-----------|-------|
| Algorithm | PBKDF2WithHmacSHA256 |
| Iterations | 100,000 |
| Key Length | 256 bits |
| Salt | 16 random bytes (SecureRandom) |
| Storage Format | `salt_hex:hash_hex` |

Passwords are never stored in plain text. The salt is generated fresh for each password and stored alongside the hash. Verification uses constant-time comparison (`MessageDigest.isEqual`) to prevent timing attacks.

### Password Rules

| Rule | Default | Configurable |
|------|---------|-------------|
| Minimum length | 6 characters | Yes |
| Maximum length | 32 characters | Yes |
| Allowed characters | Any | No restriction beyond length |
| Registration state | Must be in `REQUIRES_REGISTRATION` | No |
| Login state | Must be in `REQUIRES_LOGIN` | No |
| Duplicate registration | Blocked | No |

---

## Identity System

ValleyAuth tracks three types of identities. Each type has a unique UUID and a name prefix that prevents impersonation between types.

### Identity Types

| Type | UUID Source | Name Prefix | Example Name |
|------|-----------|-------------|-------------|
| Premium | Mojang session server | (none) | `Steve` |
| Bedrock | Floodgate UUID | `.` (dot) | `.Steve` |
| Offline | Generated from username | `-` (dash) | `-Steve` |

### Name Prefixes

Prefixes are configured in `config.yml` under the `identity` section. They serve two purposes:

1. **Impersonation prevention.** A bedrock player named "Steve" shows as `.Steve`. An offline player named "Steve" shows as `-Steve`. The premium player keeps the plain name `Steve`. Nobody can pretend to be someone else.
2. **Database keying.** The canonical name (with prefix) is used as the storage key for identity lookups.

### Username Validation

All usernames must match this pattern:

```
^[a-zA-Z0-9_]{3,16}$
```

- 3 to 16 characters
- Letters (a-z, A-Z), digits (0-9), and underscores only
- No spaces, no special characters, no Unicode

### Offline UUID Generation

When a new offline player registers, ValleyAuth generates a deterministic UUID:

```
UUID.nameUUIDFromBytes("offline:<username>".getBytes())
```

This ensures the same username always gets the same UUID, regardless of when they first join. The mapping is stored in `data/offline-uuids.json` and survives server restarts.

### Identity Cache

Identity objects are cached in memory during the plugin lifecycle. The cache is populated at startup by scanning stored data and refreshed as players join. The cache does not persist between server restarts; it rebuilds from stored data each time the server starts.

---

## VLink: Web-Based Migration

VLink is a built-in web interface that lets players initiate data migrations from a browser. It requires no external web server.

### How VLink Works

```
Player runs /migrate
        |
        v
Gets a code (like AB3F-K7MN) and a URL
        |
        v
Opens URL in browser
        |
        v
Enters the code on the landing page
        |
        v
Fills out migration details (server address, type, scope)
        |
        v
Submits the form
        |
        v
Returns to Minecraft, runs /v link <code>
        |
        v
Data migration begins
```

### VLink Code Format

Codes are short, human-readable strings designed to be typed in chat:

- Default format: `XXXX-XXXX` (8 characters with a dash in the middle)
- Character set: `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`
- Ambiguous characters removed: I, O, 0, 1 (to avoid confusion)
- Configurable length via `vlink.code-length`

### Session Lifecycle

| State | Meaning |
|-------|---------|
| PENDING | Code generated, waiting for player to enter it in browser |
| VERIFIED | Player entered code and submitted migration details in browser |
| COMPLETED | Player ran `/v link <code>` in-game, migration started |
| EXPIRED | Session expired before completion (default: 30 minutes) |
| FAILED | An error occurred during the session |

### Cleanup

Expired sessions are cleaned up automatically every 60 seconds. Used codes are purged after 24 hours.

### Web Server

The web interface runs on a built-in HTTP server using the JDK's `com.sun.net.httpserver.HttpServer`. No Tomcat, Jetty, or other external server is required.

| Setting | Default |
|---------|---------|
| Port | 8080 |
| Thread pool | 4 threads |
| SSL | Disabled by default |

### Routes

| Route | Method | What It Does |
|-------|--------|-------------|
| `/` | GET | Landing page with code input field |
| `/migrate/<code>` | GET | Migration configuration form |
| `/api/migrate/<code>` | POST | Stores migration details from the form |
| `/api/status/<code>` | GET | Returns JSON status for polling |
| `/favicon.ico` | GET | Returns 204 No Content |

### SSL / HTTPS

HTTPS is optional. To enable it:

1. Generate a JKS keystore file
2. Place it in the server directory
3. Configure the SSL section in `config.yml`:

```yaml
vlink:
  ssl:
    enabled: true
    keystore-path: "keystore.jks"
    keystore-password: "yourpassword"
    keystore-type: "JKS"
```

If SSL configuration is invalid or the keystore file is missing, the server falls back to HTTP and logs a warning.

### Domain Configuration

If you set `vlink.domain`, the displayed URL uses that domain instead of `localhost`. The port is shown only if `vlink.show-port` is `true`.

| Config | Value | Displayed URL |
|--------|-------|--------------|
| domain: `example.com`, ssl: true, show-port: false | `https://example.com/migrate/AB3F-K7MN` |
| domain: `example.com`, ssl: false, show-port: true | `http://example.com:8080/migrate/AB3F-K7MN` |
| domain: (empty), show-port: false | `http://localhost:8080/migrate/AB3F-K7MN` |

---

## Migration System

ValleyAuth moves player data between UUIDs when a player changes identity (e.g., from offline to premium). The system is designed to be safe, with locking, queuing, and rollback support.

### Migration Types

| Transition | Supported | Notes |
|-----------|-----------|-------|
| Offline to Premium | Yes | Most common case |
| Offline to Bedrock | Yes | Requires Floodgate |
| Premium to Offline | No | Not supported |
| Premium to Bedrock | No | Not supported |
| Bedrock to Offline | No | Not supported |
| Bedrock to Premium | No | Not supported |
| Premium to Premium | No | Not supported |
| Bedrock to Bedrock | No | Not supported |
| Offline to Offline | No | Not supported |

### How Migration Works

1. Player initiates migration via VLink or `/migrate`.
2. A `MigrationJob` is created with source UUID, destination UUID, and scope.
3. The job enters a queue.
4. The `MigrationManager` processes the queue every second, limited by `max-concurrent` (default: 5 simultaneous jobs).
5. For each job:
   - Source and destination identity locks are acquired (deadlock-safe ordering).
   - A `MigrationIndex` is used to locate all relevant files.
   - `FileMigrationEngine` scans each file and replaces the source UUID string with the destination UUID.
   - If LuckPerms is installed, permissions and groups are copied.
   - Any registered `MigrationProvider` instances are invoked for addon-specific data.
6. The job is marked COMPLETED or FAILED.

### Job State Machine

```
PENDING
  |
  v
QUEUED
  |
  v
RUNNING
  |
  +---> COMPLETED
  |
  +---> FAILED
```

COMPLETED and FAILED are terminal states. There is no retry mechanism for failed jobs.

### What Gets Migrated

The migration engine scans the entire `plugins/` directory at startup and indexes files by path and format. During migration, it opens each indexed file and performs a string replacement of the source UUID with the destination UUID.

**Supported file formats:**

| Format | Extensions | Migration Method |
|--------|-----------|-----------------|
| JSON | `.json` | String replacement of UUID |
| YAML | `.yml`, `.yaml` | String replacement of UUID |
| Properties | `.properties` | String replacement of UUID |
| Text | `.txt`, `.conf`, `.cfg` | String replacement of UUID |

**Excluded files:**

| Format | Reason |
|--------|--------|
| `.db`, `.sqlite`, `.sqlite3`, `.mdb` | Database files; cannot be safely migrated with string replacement |

### LuckPerms Migration

When LuckPerms is installed, ValleyAuth copies all permission nodes, groups, and meta from the source UUID to the destination UUID. This includes:

- All permission nodes with their expiry times and contexts
- Primary and secondary group assignments
- Prefix and suffix meta

The migration loads both users into LuckPerms, copies the data, and saves both. The source user data is not deleted.

### Migration Provider (Addons)

Third-party plugins can register a `MigrationProvider` to handle their own storage during migrations. This requires the `MIGRATION_PROVIDER` certificate capability. Providers receive a `MigrationContext` with source/dest UUIDs, identity information, and metadata, then handle their own data migration.

---

## Certificate System

ValleyAuth includes a certificate-based authorization system powered by ValleyCert. This controls which plugins are allowed to use protected features like VLink and migration.

### How Certificates Work

1. On startup, ValleyAuth requests a certificate from the ValleyCert Certificate Authority (CA).
2. The CA issues a certificate with specific capabilities (what the plugin is allowed to do).
3. The certificate is cached locally in `data/core-cert.json`.
4. The certificate is valid for 90 days.
5. If the CA is unreachable, ValleyAuth runs in offline mode using cached certificates.

### Certificate Capabilities

| Capability | What It Grants |
|-----------|---------------|
| `VLINK` | Access to the VLink web interface |
| `IDENTITY_LINK` | Ability to link identities |
| `RANK_SHARE` | Permission to share ranks between accounts |
| `MIGRATION_PROVIDER` | Register as a migration data provider |
| `MIGRATION_ACCESS` | Access to migration features |
| `CERTIFICATE_MANAGEMENT` | Manage certificates for other plugins |

### Plugin Certificate Validation

When another plugin tries to use a protected feature, ValleyAuth validates its certificate:

1. Check the local certificate cache.
2. If cached and not expired, verify the required capability is present.
3. If the CA is reachable, perform an additional validation request.
4. If the CA is unreachable and no cache exists, deny access.

### Revocation

Certificates can be revoked by the CA. Revocation is checked via an encrypted timestamp (AES-GCM). The revocation key is stored at `data/keys/revocation.key`. If decryption fails or the revocation timestamp is newer than the certificate's issuance date, the certificate is considered revoked.

### Unsafe Plugin Detection

If a plugin fails certificate validation, ValleyAuth marks it as "UNSAFE" in `data/unsafe-plugins.json`. Unsafe plugins are flagged with a warning to server operators on every join. The `valleyauth admin` can resolve (clear) unsafe status.

### Certificate Configuration

```yaml
certificate:
  api-url: "https://cert.strawberry.dpdns.org"
  docs-url: "https://docs.valleyrealm.qd.je/certificates"

security:
  enforce-certificate-authorization: true
  log-security-events: true
```

If you don't use the ValleyCert system, set `enforce-certificate-authorization` to `false`. This disables all certificate checks and allows all plugins to use protected features.

---

## Integration Support

### Floodgate / Geyser (Bedrock Support)

ValleyAuth detects bedrock players through Floodgate using reflection. This means there is no compile-time dependency on Floodgate; it works whether Floodgate is installed or not.

When a bedrock player joins:

1. ValleyAuth checks if the UUID belongs to a Floodgate player.
2. If yes, the identity type is set to BEDROCK.
3. The player's name gets the bedrock prefix (default: `.`).
4. No manual authentication is required.

If Floodgate is not installed, bedrock players are treated as offline players and must register/login.

### LuckPerms (Permission Migration)

ValleyAuth directly depends on the LuckPerms API. When LuckPerms is installed:

1. Permission migration runs automatically during data migration.
2. All permission nodes, groups, and meta are copied from the old UUID to the new UUID.
3. The source UUID's LuckPerms data is not deleted.

If LuckPerms is not installed, permission data is not migrated. Only file-based data (worlds, inventories, plugin configs) is moved.

---

## Configuration Reference

All configuration is in `plugins/ValleyAuth/config.yml`. After editing, run `/valleyauth reload` or restart the server.

### Identity Settings

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `identity.premium-prefix` | String | `""` | Prefix for premium (Mojang) player names. Empty string means no prefix. |
| `identity.bedrock-prefix` | String | `"."` | Prefix for bedrock player names. Default dot prevents impersonation. |
| `identity.offline-prefix` | String | `"-"` | Prefix for offline player names. Default dash prevents impersonation. |

### Migration Settings

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `migration.max-concurrent` | int | `5` | Maximum number of migrations running at the same time. Higher values use more resources. |
| `migration.web-interface.enabled` | boolean | `true` | Enable or disable the built-in VLink web server. |
| `migration.web-interface.port` | int | `8080` | Port for the VLink web server. Change if another service uses this port. |

### VLink Settings

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `vlink.domain` | String | `""` | Your server's domain for the web interface URL. Leave empty for localhost. |
| `vlink.show-port` | boolean | `false` | Whether to include the port number in displayed URLs. |
| `vlink.code-length` | int | `8` | Length of VLink codes. Longer codes are harder to guess. |
| `vlink.expiry-minutes` | int | `30` | How many minutes a VLink session stays valid before expiring. |

### VLink SSL Settings

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `vlink.ssl.enabled` | boolean | `false` | Enable HTTPS for the web interface. |
| `vlink.ssl.keystore-path` | String | `"keystore.jks"` | Path to the JKS keystore file, relative to the server directory. |
| `vlink.ssl.keystore-password` | String | `""` | Password for the keystore. |
| `vlink.ssl.keystore-type` | String | `"JKS"` | Keystore type. JKS is the standard. |

### Authentication Settings

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `authentication.login-timeout-seconds` | int | `60` | Seconds a player has to log in before being kicked. |
| `authentication.max-password-length` | int | `32` | Maximum allowed password length. |
| `authentication.min-password-length` | int | `6` | Minimum required password length. |

### Security Settings

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `security.enforce-certificate-authorization` | boolean | `true` | Enable certificate validation for protected features. Set to false if you don't use ValleyCert. |
| `security.log-security-events` | boolean | `true` | Log security events (certificate failures, unsafe plugins) to the server console. |

### Certificate Settings

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `certificate.api-url` | String | `"https://cert.strawberry.dpdns.org"` | URL of the ValleyCert Certificate Authority. |
| `certificate.docs-url` | String | `"https://docs.valleyrealm.qd.je/certificates"` | URL shown to players when they need certificate help. |

### Logging Settings

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `logging.verbose` | boolean | `false` | Enable detailed logging for debugging. Produces significantly more console output. |

### Full Default Config

```yaml
identity:
  premium-prefix: ""
  bedrock-prefix: "."
  offline-prefix: "-"

migration:
  max-concurrent: 5
  web-interface:
    enabled: true
    port: 8080

vlink:
  domain: ""
  show-port: false
  code-length: 8
  expiry-minutes: 30
  ssl:
    enabled: false
    keystore-path: "keystore.jks"
    keystore-password: ""
    keystore-type: "JKS"

authentication:
  login-timeout-seconds: 60
  max-password-length: 32
  min-password-length: 6

security:
  enforce-certificate-authorization: true
  log-security-events: true

certificate:
  api-url: "https://cert.strawberry.dpdns.org"
  docs-url: "https://docs.valleyrealm.qd.je/certificates"

logging:
  verbose: false
```

---

## Command Reference

### /register

Registers a new password for an offline-mode player.

```
/register <password>
```

| Parameter | Required | Description |
|-----------|----------|-------------|
| `<password>` | Yes | The password to register. Must be between 6 and 32 characters. |

**Examples:**

```
/register mySecurePass123
/register hunter2
```

**Behavior:**
- Only works if the player is in `REQUIRES_REGISTRATION` state.
- If the player already has a password, registration is blocked.
- The password is hashed immediately and stored. The plain text is never saved.
- After successful registration, the player is automatically authenticated.

**Error messages:**
- "You are already registered." — Player already has a password.
- "Password must be at least X characters." — Password too short.
- "Password must be at most X characters." — Password too long.

---

### /login

Logs in with a previously registered password.

```
/login <password>
```

| Parameter | Required | Description |
|-----------|----------|-------------|
| `<password>` | Yes | The password to log in with. |

**Examples:**

```
/login mySecurePass123
/login hunter2
```

**Behavior:**
- Only works if the player is in `REQUIRES_LOGIN` state.
- If the password is correct, the player is authenticated and the login timer stops.
- If the password is wrong, the player stays in `REQUIRES_LOGIN` state and can try again.

**Error messages:**
- "Invalid password." — Password does not match.
- "You are not registered yet." — Player needs to register first.

---

### /migrate

Opens the VLink web interface for data migration.

```
/migrate
/migrate status
/migrate help
```

| Subcommand | Description |
|-----------|-------------|
| (no args) | Creates a new VLink session targeting premium identity. Shows the code and URL. |
| `status` | Shows the status of any pending or in-progress migration job. |
| `help` | Shows usage information. |

**Examples:**

```
/migrate
/migrate status
/migrate help
```

**Behavior:**
- Creates a VLink session if one doesn't already exist for the player.
- If a pending session exists, the player is redirected to use it.
- Shows the VLink code and the URL to open in a browser.
- Warns if SSL is not enabled (the URL will be HTTP, not HTTPS).

**Output example:**

```
[ValleyAuth] Migration VLink
[ValleyAuth] Code: AB3F-K7MN
[ValleyAuth] Open: http://example.com/migrate/AB3F-K7MN
[ValleyAuth] Enter the code in your browser, then run /v link AB3F-K7MN in-game.
```

---

### /v

Links a VLink code to initiate migration.

```
/v link <code>
```

| Parameter | Required | Description |
|-----------|----------|-------------|
| `<code>` | Yes | The VLink code shown by /migrate. |

**Examples:**

```
/v link AB3F-K7MN
```

**Behavior:**
- The player must be authenticated first.
- Verifies the code, checks expiration, and starts the migration.
- Shows the migration progress.

**Error messages:**
- "Invalid or expired code." — Code not found or expired.
- "Code already used." — The code was already consumed.
- "You must be logged in first." — Player hasn't authenticated.

---

### /valleyauth

Admin command for managing the plugin.

```
/valleyauth status
/valleyauth reload
/valleyauth help
```

| Subcommand | Permission | Description |
|-----------|-----------|-------------|
| `status` | `valleyauth.admin` | Shows plugin version, storage stats, certificate status, and unsafe plugins. |
| `reload` | `valleyauth.admin` | Reloads `config.yml` without restarting the server. |
| `help` | `valleyauth.admin` | Shows available subcommands. |

**Tab completion:** `status`, `reload`, `help`

---

## Permissions Reference

| Permission | Default | Description |
|-----------|---------|-------------|
| `valleyauth.vlink` | `true` (all players) | Allows using `/v link <code>` to verify a VLink code. |
| `valleyauth.register` | `true` (all players) | Allows using `/register <password>` to register a new account. |
| `valleyauth.login` | `true` (all players) | Allows using `/login <password>` to log in. |
| `valleyauth.admin` | `op` (operators only) | Allows using `/valleyauth status`, `/valleyauth reload`, and `/valleyauth help`. |
| `valleyauth.migration.status` | `true` (all players) | Allows viewing migration status. |
| `valleyauth.migrate` | `true` (all players) | Allows using `/migrate` to start the migration process. |

---

## Architecture

### System Overview

```
+------------------------------------------------------------------+
|                        ValleyAuth Plugin                          |
+------------------------------------------------------------------+
|                                                                  |
|  +------------------+    +------------------+    +--------------+ |
|  | PlayerConnection |    | Authentication   |    | Identity     | |
|  | Listener         |--->| Manager          |--->| Manager      | |
|  | (join/quit/chat/ |    | (register/login/ |    | (cache/UUID/ | |
|  |  move/timer)     |    |  PBKDF2/state)   |    |  validation) | |
|  +------------------+    +------------------+    +--------------+ |
|           |                       |                     |         |
|           v                       v                     v         |
|  +------------------+    +------------------+    +--------------+ |
|  | Command          |    | Storage          |    | Floodgate    | |
|  | Executors        |    | Manager          |    | Adapter      | |
|  | (register/login/ |    | (JSON files:     |    | (reflection: | |
|  |  vlink/migrate/  |    |  uuids/passwords |    |  bedrock     | |
|  |  valleyauth)     |    |  /unsafe-addons) |    |  detection)  | |
|  +------------------+    +------------------+    +--------------+ |
|           |                       |                     |         |
|           v                       v                     v         |
|  +------------------+    +------------------+    +--------------+ |
|  | VLink Manager    |    | Migration Manager|    | LuckPerms    | |
|  | (sessions/codes/ |    | (queue/locking/  |    | Adapter      | |
|  |  cleanup)        |    |  execution)      |    | (permission  | |
|  +------------------+    +------------------+    |  copy)       | |
|           |                       |              +--------------+ |
|           v                       v                               |
|  +------------------+    +------------------+                     |
|  | VLink Web Server |    | File Migration   |                     |
|  | (HTTP/HTTPS:     |    | Engine           |                     |
|  |  routes/forms/   |    | (UUID string     |                     |
|  |  polling)        |    |  replacement)    |                     |
|  +------------------+    +------------------+                     |
|           |                       |                               |
|           v                       v                               |
|  +------------------+    +------------------+                     |
|  | ValleyCert       |    | Certificate      |                     |
|  | Client           |    | Enforcer         |                     |
|  | (CA requests/    |    | (validation/     |                     |
|  |  retry/cache)    |    |  UNSAFE marking) |                     |
|  +------------------+    +------------------+                     |
|                           |                                       |
|                           v                                       |
|                    +------------------+                            |
|                    | Unsafe Addon     |                            |
|                    | Manager          |                            |
|                    | (detection/      |                            |
|                    |  persistence)    |                            |
|                    +------------------+                            |
+------------------------------------------------------------------+
```

### Data Flow: Player Join

```
Player connects to server
        |
        v
PlayerConnectionListener.onJoin()
        |
        +---> Notify UnsafeAddonManager (warn OPs about flagged plugins)
        +---> Notify CertificateEnforcer (warn OPs about cert issues)
        +---> FloodgateAdapter.resolveIdentityType()
        |         |
        |         +---> Floodgate present? --> Check UUID --> BEDROCK
        |         +---> Online mode? --> PREMIUM
        |         +---> Otherwise --> OFFLINE
        |
        +---> IdentityManager.getOrCreate()
        |
        +---> AuthenticationManager.onPlayerJoin()
        |         |
        |         +---> PREMIUM/BEDROCK --> AUTHENTICATED (no action needed)
        |         +---> OFFLINE + has password --> REQUIRES_LOGIN (start timer)
        |         +---> OFFLINE + no password --> REQUIRES_REGISTRATION (start timer)
        |
        +---> Start auth timer (if offline)
        |
        +---> Check for pending migration jobs
```

### Data Flow: Migration

```
/migrate (or VLink web form)
        |
        v
VLinkManager.createSession()
        |
        +---> Validate certificate (if enforced)
        +---> Validate migration is allowed (OFFLINE->PREMIUM or OFFLINE->BEDROCK)
        +---> Generate code + session ID
        +---> Return session details
        |
        v
Player enters code in browser (/v link <code>)
        |
        v
VLinkManager.verifyCode()
        |
        +---> Check code not used (replay protection)
        +---> Check not expired
        +---> Mark code used
        +---> Resolve destination identity
        +---> MigrationManager.queueMigration()
        |
        v
MigrationManager (background thread pool)
        |
        +---> Acquire identity locks (deadlock-safe ordering)
        +---> Get MigrationIndex (cached file list)
        +---> FileMigrationEngine.migrateFromIndex()
        |         |
        |         +---> For each indexed file:
        |                   Open file, replace source UUID with dest UUID, close
        |
        +---> LuckPermsAdapter.migratePermissions() (if available)
        |
        +---> Invoke MigrationProviders (if any registered)
        |
        +---> Mark job COMPLETED or FAILED
        |
        v
Player sees migration result
```

### Storage Structure

```
plugins/ValleyAuth/
  data/
    offline-uuids.json    Map<lowercase_username, uuid_string>
    passwords.json        Map<uuid_string, "salt_hex:hash_hex">
    unsafe-plugins.json   Set<plugin_name>
    core-cert.json        Core ValleyCert certificate
    <pluginId>-cert.json  Per-plugin certificate
    keys/
      revocation.key      AES key for revocation timestamp decryption
  migrations/             Migration job files
  vlink/                  VLink session data
  certs/                  Certificate cache
```

---

## Troubleshooting

### Player can't register

**Symptom:** Player types `/register somepassword` and gets "You are already registered."

**Cause:** The server already has a password stored for this UUID. This happens if the player registered before, or if a different player used the same UUID.

**Fix:** If you're an admin and need to reset a player's password, delete their entry from `plugins/ValleyAuth/data/passwords.json` and restart the server or reload the plugin.

---

### Player gets kicked after 60 seconds

**Symptom:** A new offline player joins, sees the register/login prompt, but gets kicked after about a minute.

**Cause:** The `login-timeout-seconds` expired before the player authenticated.

**Fix:** Increase the timeout in `config.yml`:

```yaml
authentication:
  login-timeout-seconds: 120
```

Or instruct the player to register immediately after joining.

---

### VLink web page not loading

**Symptom:** Player opens the VLink URL and gets "This site can't be reached" or a connection error.

**Possible causes:**

1. The web interface is disabled. Check `migration.web-interface.enabled` in config.
2. The port is blocked by a firewall. Open port 8080 (or your configured port).
3. The port is already in use by another service. Change `migration.web-interface.port`.
4. If using a remote server, the domain or IP address in the URL is wrong.

**Fix:** Check the server console for ValleyAuth startup messages. Look for "VLink web server started on port XXXX" or error messages about port conflicts.

---

### Bedrock players treated as offline

**Symptom:** Bedrock players have to register and login like offline players.

**Cause:** Floodgate and/or Geyser are not installed, or not loaded before ValleyAuth.

**Fix:**

1. Install both Geyser and Floodgate in the `plugins/` folder.
2. Make sure they load before ValleyAuth. In `plugin.yml`, ValleyAuth declares Floodgate as a soft dependency, so it should load after. If this isn't happening, check the server console for load order.
3. Restart the server completely (not just `/reload`).

---

### Migration fails or shows no files

**Symptom:** Migration completes instantly with 0 files processed, or fails with an error.

**Possible causes:**

1. The source player has no plugin data to migrate.
2. The plugin storage directory is empty or doesn't contain indexed file types.
3. Certificate validation failed and the migration was blocked.

**Fix:** Run `/valleyauth status` to check the storage scan results and certificate status. Check the server console for error messages during migration.

---

### Certificate errors in console

**Symptom:** Repeated warnings about certificate validation failures.

**Cause:** The ValleyCert CA is unreachable or the certificate has expired.

**Fix:**

1. Check if `https://cert.strawberry.dpdns.org` is reachable from your server.
2. If you don't use ValleyCert, disable enforcement:

```yaml
security:
  enforce-certificate-authorization: false
```

3. Run `/valleyauth reload` after changing the setting.

---

### Config changes not taking effect

**Symptom:** You edited `config.yml` but the plugin still uses old settings.

**Cause:** Config is loaded at startup. Changes require a reload or restart.

**Fix:** Run `/valleyauth reload` or restart the server. Some settings (like `migration.web-interface.port`) only take effect on startup and require a full restart.

---

### Offline players with the same name as premium players

**Symptom:** A premium player can't join because an offline player already registered that name.

**Cause:** ValleyAuth protects premium usernames. If someone registered an offline account with a premium player's name, the premium player is blocked.

**Fix:** This is by design. The admin needs to delete the offline player's data from `passwords.json` and `offline-uuids.json`, then restart/reload. The premium player will then be able to join.

---

### Password reset for a player

**Symptom:** A player forgot their password and can't log in.

**Cause:** ValleyAuth does not have a built-in password reset command.

**Fix:** An admin must manually remove the player's entry from `plugins/ValleyAuth/data/passwords.json`. The player will then need to re-register. Edit the JSON file, find the entry matching the player's UUID, and delete it.

---

### Migration job stuck in queue

**Symptom:** A migration job shows as QUEUED but never starts.

**Cause:** The maximum concurrent migration limit is reached, or a deadlock is preventing progress.

**Fix:**

1. Check `/migrate status` to see current job states.
2. Increase `migration.max-concurrent` if needed.
3. If a job appears stuck, restarting the server will clear the queue. The job will need to be re-initiated by the player.

---

## FAQ

### Does ValleyAuth work on Spigot or other server forks?

No. ValleyAuth requires Paper 1.21 or newer. Spigot, CraftBukkit, and other forks are not supported.

---

### Can I use ValleyAuth with online-mode=true?

ValleyAuth is designed for offline-mode servers. If your server runs in online-mode, premium players are already authenticated by Mojang and don't need ValleyAuth. However, the plugin will still function; it just won't require manual authentication for premium players.

---

### Is it safe to run ValleyAuth in production?

ValleyAuth is currently v0.2.0-alpha. It is functional but has not been through a full release cycle. Test thoroughly on a development server before deploying to production. Back up your server data before enabling the migration features.

---

### What happens to player data if the server crashes during migration?

Migration jobs are designed to be atomic per file. If the server crashes mid-migration, some files may be partially migrated. The migration system does not currently support automatic rollback. You would need to restore from a backup.

---

### Can I change the name prefixes?

Yes. Edit the identity section in `config.yml`:

```yaml
identity:
  premium-prefix: ""
  bedrock-prefix: "."
  offline-prefix: "-"
```

Changing prefixes after players have joined will cause confusion, since existing names in plugin storage won't be updated. Plan your prefix scheme before your server goes live.

---

### How do I disable the VLink web interface?

Set this in `config.yml`:

```yaml
migration:
  web-interface:
    enabled: false
```

This disables the built-in HTTP server entirely. Players can still use the `/migrate` and `/v link` commands, but they won't get a web URL to open.

---

### Can I use my own web server for VLink?

Not directly. The VLink web interface is built into ValleyAuth and runs on its own HTTP server. You could place a reverse proxy (like nginx) in front of port 8080, but the plugin itself handles all the routing and session management internally.

---

### How does ValleyAuth prevent impersonation?

Name prefixes. A premium player named "Steve" appears as `Steve`. A bedrock player with the same name appears as `.Steve`. An offline player appears as `-Steve`. Since different identity types use different UUIDs and different display names, no player can impersonate another.

Additionally, ValleyAuth checks if a username is "protected" by a premium identity before allowing an offline player to register with that name.

---

### Does ValleyAuth store passwords in plain text?

No. Passwords are hashed using PBKDF2 with 100,000 iterations of SHA-256, salted with 16 random bytes. The plain text password is never stored anywhere. The storage format is `salt_hex:hash_hex`.

---

### Can players change their password?

Not currently. There is no `/changepassword` command. If a player needs to change their password, an admin must delete their entry from `passwords.json` and the player must re-register.

---

### What files does migration actually move?

ValleyAuth scans the entire `plugins/` directory at startup and indexes all files with these extensions: `.json`, `.yml`, `.yaml`, `.properties`, `.txt`, `.conf`, `.cfg`. During migration, it opens each indexed file and replaces occurrences of the source UUID with the destination UUID.

Database files (`.db`, `.sqlite`, `.sqlite3`, `.mdb`) are excluded because they cannot be safely migrated with string replacement.

---

### Does migration work for WorldEdit selections or region data?

It depends on the plugin. If the plugin stores data in JSON, YAML, or properties files with UUID keys, migration will work. If it uses databases or custom binary formats, migration will not work for that data.

---

### Can I migrate from premium to offline?

No. The only supported migration paths are:

- Offline to Premium
- Offline to Bedrock

This is by design. Migration is meant for players upgrading their account, not downgrading.

---

### How do I check if ValleyAuth is running correctly?

Run `/valleyauth status` in game. This shows:

- Plugin version
- Number of indexed files for migration
- Certificate status (valid/expired/offline)
- Number of unsafe plugins detected

You can also check the server console for ValleyAuth startup messages. A healthy startup shows all 5 initialization phases completing successfully.

---

### The console is too spammy. How do I reduce output?

Set `logging.verbose` to `false` in `config.yml` (it's false by default). If you previously set it to `true` for debugging, turn it back off.

---

## Changelog

### v0.2.0-alpha

- Initial alpha release
- Offline-mode authentication with PBKDF2 password hashing
- Three identity types: Premium, Bedrock, Offline
- VLink web-based migration interface
- Player data migration with file-based UUID replacement
- Certificate system integration with ValleyCert
- LuckPerms permission migration
- Floodgate/Geyser bedrock support
- Unsafe addon detection
- Admin commands and status reporting
