# ValleyAuth

![Version](https://img.shields.io/badge/version-1.0.0-blue) ![License](https://img.shields.io/badge/license-MIT-green) ![API](https://img.shields.io/badge/Paper-1.21%2B-orange)

Opinionated authentication, identity management, and player-data migration for Paper servers.

## Features

- **Offline Authentication**: Register and login system for cracked/offline-mode servers with password hashing
- **VLink Web Interface**: Built-in HTTPS web server for browser-based migration configuration
- **Certificate Authority**: Integrated with ValleyCert for plugin certificate validation and capability gating
- **Migration System**: Offline to premium player migration with queue management and progress tracking
- **LuckPerms Integration**: Automatic permission migration when transferring between identities
- **Bedrock Support**: Floodgate adapter for Bedrock Edition players with automatic identity prefixing
- **Identity Management**: Configurable prefixes for premium, bedrock, and offline players
- **Unsafe Addon Detection**: Scans for potentially dangerous server plugins
- **SSL Support**: Built-in TLS termination for the web interface, or reverse proxy passthrough

## Requirements

| Dependency | Version | Required |
|---|---|---|
| Paper | 1.21+ | Yes |
| JDK | 21+ | Yes |
| LuckPerms | 5.x | Optional |
| Floodgate | 2.2.x | Optional |

## Installation

1. Download the latest `ValleyAuth.jar` from [Releases](https://github.com/SabeeirSharrma/ValleyAuth/releases)
2. Place the JAR in your server's `plugins/` directory
3. Start or restart the server
4. Edit `plugins/ValleyAuth/config.yml` to configure your domain and certificate settings
5. Restart the server again

## Quick Start

1. Set `vlink.domain` in `config.yml` to your server's public domain (e.g., `auth.example.com`)
2. Start the server. ValleyAuth will attempt to obtain a certificate from the ValleyCert CA on first launch
3. Players connect and run `/register <password>` to create an account
4. On subsequent joins, players run `/login <password>` to authenticate

## Commands

| Command | Description | Permission |
|---|---|---|
| `/register <password>` | Register a new offline account | `valleyauth.register` |
| `/login <password>` | Login to your offline account | `valleyauth.login` |
| `/migrate` | Start an offline-to-premium migration session | `valleyauth.migrate` |
| `/migrate status` | Check your migration job progress | `valleyauth.migrate` |
| `/migrate help` | Show migration help | `valleyauth.migrate` |
| `/v link <code>` | Verify a VLink migration code in-game | `valleyauth.vlink` |
| `/valleyauth status` | View plugin and certificate status | `valleyauth.admin` |
| `/valleyauth reload` | Reload configuration | `valleyauth.admin` |
| `/valleyauth help` | Show admin help | `valleyauth.admin` |

All player commands (`register`, `login`, `migrate`, `v link`) default to allowed for all players. Admin commands require operator status.

## Configuration

Full reference for `plugins/ValleyAuth/config.yml`:

```yaml
# Identity prefixes for player name display
identity:
  premium-prefix: ""        # Prefix for Java Edition premium players
  bedrock-prefix: "."       # Prefix for Bedrock Edition players (via Floodgate)
  offline-prefix: "-"       # Prefix for offline/cracked players

# Migration queue settings
migration:
  max-concurrent: 5         # Max simultaneous migration jobs
  web-interface:
    enabled: true           # Enable the built-in web interface
    port: 8080              # Port for the web server

# VLink settings (browser-based migration flow)
vlink:
  domain: ""                # Public domain for migration URLs (e.g., auth.example.com)
  show-port: false          # Include port in URLs (set true if no reverse proxy)
  code-length: 8            # Length of VLink verification codes
  expiry-minutes: 30        # Minutes before a VLink session expires
  ssl:
    enabled: false          # Enable TLS on the web server
    keystore-path: "keystore.jks"   # Path to keystore (relative to plugin data folder)
    keystore-password: ""   # Keystore password
    keystore-type: "JKS"    # Keystore format (JKS, PKCS12, etc.)

# Authentication rules
authentication:
  login-timeout-seconds: 60   # Seconds before an unauthenticated player is kicked
  max-password-length: 32     # Maximum password length
  min-password-length: 6      # Minimum password length

# Security settings
security:
  enforce-certificate-authorization: true   # Require valid certs for plugin capabilities
  log-security-events: true                 # Log authentication and cert events

# Certificate Authority
certificate:
  api-url: ""                               # ValleyCert API URL (e.g., https://cert.valleyrealm.qd.je)
  docs-url: "https://docs.valleyrealm.qd.je/certificates"  # Shown in security warnings

# Logging
logging:
  verbose: false             # Enable debug-level logging
```

## Certificate Flow

ValleyAuth uses a certificate authority (ValleyCert) to gate plugin capabilities. Here's what happens on first startup:

1. **Request sent**: ValleyAuth contacts the ValleyCert API at `certificate.api-url` and requests a core certificate with capabilities: `VLINK`, `IDENTITY_LINK`, `RANK_SHARE`, `MIGRATION_PROVIDER`, `MIGRATION_ACCESS`, `CERTIFICATE_MANAGEMENT`
2. **Certificate issued**: The CA signs and returns a certificate with a 90-day validity window
3. **Certificate cached**: Stored locally at `plugins/ValleyAuth/data/core-cert.json` for offline use
4. **Validation**: On subsequent startups, the cached certificate is loaded. If expired, a renewal is requested. If the CA is reachable, certificates are also validated against the CA in real time
5. **Offline fallback**: If the CA is unreachable, ValleyAuth runs in offline mode using only cached certificates

The CA status is visible in-game via `/valleyauth status`.

## Migration Guide

ValleyAuth migrates offline/cracked players to premium (or between identity types) using a web-based flow.

### Step by step

1. **Start migration**: Run `/migrate` in-game. You'll receive a verification code and a URL
2. **Open the web interface**: Click the link (or open it manually). Fill in your source server details:
   - Server IP/address
   - Server type (Java or Bedrock)
   - Migration scope (player data, inventories, advancements, statistics)
3. **Generate verification**: Click "Generate Verification" on the web page
4. **Verify in-game**: Copy the `/v link <code>` command and run it in Minecraft chat
5. **Migration queued**: Once verified, the migration is queued. Check progress with `/migrate status`

### What gets migrated

- Player position and game mode
- Inventory contents and equipment
- Advancements
- Statistics
- LuckPerms permissions, groups, and meta (if LuckPerms is installed)

### Concurrency

Up to 5 migrations run simultaneously (configurable via `migration.max-concurrent`). Additional jobs are queued and processed in order.

## LuckPerms Integration

ValleyAuth automatically detects LuckPerms at startup. When present:

- **Permission migration**: All permission nodes, group assignments, contexts, and expiry data are copied from the old UUID to the new UUID during migration
- **No configuration needed**: The adapter hooks into LuckPerms via its public API
- **Graceful fallback**: If LuckPerms isn't installed, permission migration is silently skipped

The adapter is listed as a soft dependency, so LuckPerms is never required.

## Reverse Proxy Setup

If you run a reverse proxy (Caddy, Nginx, Traefik) in front of your server, point it at the VLink web interface port (default `8080`).

### Caddy

```
auth.example.com {
    reverse_proxy localhost:8080
}
```

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

### show-port setting

Set `vlink.show-port: false` (default) when using a reverse proxy so URLs appear clean: `https://auth.example.com/migrate/...`

Set `vlink.show-port: true` when players connect directly to the server IP and port, so URLs include the port: `https://auth.example.com:25565/migrate/...`

## Building from Source

Requires JDK 21+.

```bash
# Clone the repository
git clone https://github.com/SabeeirSharrma/ValleyAuth.git
cd ValleyAuth

# Build the plugin
./gradlew build

# The output JAR is at:
# build/libs/ValleyAuth-<version>.jar
```

### Available tasks

```bash
./gradlew build          # Compile and package
./gradlew clean          # Remove build artifacts
./gradlew runServer      # Start a test server with the plugin (requires Paper)
```

## License

MIT License. See [LICENSE](LICENSE) for details.
