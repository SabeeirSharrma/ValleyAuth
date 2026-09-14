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
- Floodgate (optional, see [crossplay-support](#crossplay-support))

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

## Crossplay Support

**IMPORTANT FOR SERVERS USING GEYSERMC FOR CROSSPLAY**

If your server has crossplay and is using GeyserMC for crossplay, Floodgate is required for ValleyAuth.

### Why is floodgate required to use ValleyAuth for my crossplay server?

In short: ValleyAuth's forced auth flow works by intercepting the encryption handshake. When a player connects, ValleyAuth sends an EncryptionRequest. Java clients respond with an EncryptionResponse that proves they have a valid Mojang account. Bedrock clients can't do this - they're not Java clients. GeyserMC handles the protocol translation, but the authentication handshake is fundamentally different.
Without Floodgate, ValleyAuth would send an EncryptionRequest to a Bedrock player, the Bedrock client would either reject it or respond incorrectly, and the player would be stuck in a verification loop or kicked. Floodgate tells ValleyAuth "this is a Bedrock player, don't bother with the Java encryption handshake - just let them in."

<details>
<summmary>Long Version</summary>

Floodgate isn't required to use ValleyAuth in typical situations. But for crossplay servers (Java + Bedrock), it's practically essential. Here's the full picture:

**Without Floodgate:**

- Bedrock players connect via GeyserMC, which translates Bedrock protocol to Java
- GeyserMC assigns them a UUID derived from their XUID (Xbox User ID)
- GeyserMC adds a . prefix to their username (e.g., .Steve)
- ValleyAuth has no way to know if a player is Bedrock or Java without Floodgate
- Bedrock players would be forced through /register and /login like any other offline player
- Their UUID and username prefix would be inconsistent across sessions

**With Floodgate:**

- Floodgate sits on top of GeyserMC and provides a proper API to identify Bedrock players
- ValleyAuth calls `FloodgateApi.getInstance().isFloodgatePlayer(uuid)` (Basically the Floodgate API) to detect Bedrock connections
- When a Bedrock player is detected, ValleyAuth immediately assigns them a Bedrock identity (. prefix + Floodgate UUID)
- Bedrock players skip the forced auth flow entirely - they don't need to /register or /login
- Their identity is stable and consistent across sessions

**The technical reason:**

ValleyAuth's forced auth flow works by intercepting the encryption handshake. When a player connects, ValleyAuth sends an EncryptionRequest. Java clients respond with an EncryptionResponse that proves they have a valid Mojang account. Bedrock clients can't do this - they're not Java clients. GeyserMC handles the protocol translation, but the authentication handshake is fundamentally different.
Without Floodgate, ValleyAuth would send an EncryptionRequest to a Bedrock player, the Bedrock client would either reject it or respond incorrectly, and the player would be stuck in a verification loop or kicked. Floodgate tells ValleyAuth "this is a Bedrock player, don't bother with the Java encryption handshake - just let them in."

For your server specifically:

Since you're running a crossplay server, Floodgate + GeyserMC means:

- Java players with Mojang accounts → Premium identity, no password needed
- Java players without Mojang accounts (TLauncher, etc.) → Offline identity, must /register or /login
- Bedrock players → Bedrock identity, no password needed (handled by Floodgate)
Without Floodgate, all Bedrock players would be treated as Offline players and forced to authenticate, which they can't do through the Java auth flow.

</details>

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
