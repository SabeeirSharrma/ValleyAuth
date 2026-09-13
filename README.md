# ValleyAuth

Authentication, identity, and player-data migration plugin for Paper servers (1.21+).

## What It Does

ValleyAuth forces players on offline-mode servers to prove Mojang account ownership. Premium players are recognized automatically and bypass `/register` and `/login`. Players without a valid Mojang account are treated as Offline and must authenticate with a password.

## How It Works

1. Player connects to the server
2. ValleyAuth sends an EncryptionRequest (same as online-mode servers)
3. Player responds with EncryptionResponse (proves they have the Mojang client)
4. ValleyAuth verifies the session with Mojang's session server
5. Verification succeeds - Premium identity (no password needed)
6. Verification fails - Offline identity (`-` prefix + custom UUID, must `/register` or `/login`)

## Identity Types

| Type | Prefix | UUID Source | Auth Required |
|------|--------|-------------|---------------|
| Premium | (none) | Mojang | No |
| Bedrock | `.` | Floodgate | No |
| Offline | `-` | ValleyAuth (deterministic) | Yes |

## Requirements

- Paper 1.21+
- Java 21+
- packetevents 2.13.0 (required for forced auth)
- ProtocolLib 5.4.0 (optional fallback)
- Floodgate (optional, for Bedrock players)

## Build

```bash
cd ValleyAuth
./gradlew build
```

Output: `ValleyAuth/build/libs/ValleyAuth-0.1.0-alpha.jar`

## Install

1. Drop `ValleyAuth-0.1.0-alpha.jar` into your server's `plugins/` folder
2. Install packetevents (required) - https://www.spigotmc.org/resources/packetevents.18637/
3. Restart the server

## Commands

| Command | Description |
|---------|-------------|
| `/register <password> <password>` | Register a new Offline account |
| `/login <password>` | Log in to an existing Offline account |

## Configuration

ValleyAuth detects the server's `online-mode` setting automatically:

- `online-mode=true` - ValleyAuth does nothing, vanilla handles auth
- `online-mode=false` - ValleyAuth forces Mojang verification for all non-Floodgate players

## Floodgate Support

If Floodgate is installed, Bedrock players are detected automatically and routed to their Floodgate identity. No configuration needed.

## Project Structure

```
ValleyRealm/
  ValleyAuth/     - The plugin (core auth, identity, enforcement)
  ValleyCert/     - Client certificate utility (planned)
  certapi/        - Standalone CA server (planned)
  spec.md         - Full specification
```

## Stage Status

- [x] Stage 1: Auth system (forced Mojang verification, Premium/Offline routing, password storage)
- [ ] Stage 2: Database and migration bookkeeping
- [ ] Stage 3: Identity prefix system
- [ ] Stage 4: Anti-cheat integration
- [ ] Stage 5: Command system
- [ ] Stage 6: Configuration
- [ ] Stage 7: Cert API
- [ ] Stage 8: Client cert utility

## Notes

- Mojang sessions are one-time-use. Restart your client between connecting to different servers.
- ValleyAuth generates RSA keypairs at startup for the encryption handshake.
- Offline UUIDs are deterministic (SHA-1 of username) and persist across restarts.
- Premium accounts are recognized by UUID, not username - name changes are handled correctly.
