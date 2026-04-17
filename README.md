# Geyser Education Extension

Optional [Geyser](https://geysermc.org/) extension that adds three ways for Minecraft Education Edition students to discover and join your server:

1. **Server List** - Broadcasts your server to Education Edition's built-in server browser. Students see the server automatically and click Play.
2. **Join Codes** - Creates join codes that students can enter in the Education Edition "Join Code" screen, or click a share link.
3. **Connection ID** - A single 10-digit ID that students can enter directly in Education Edition's connection dialog to join cross-tenant, bypassing join codes entirely.

**This extension is not required for education clients to connect.** Education support in [EduGeyser](https://github.com/SendableMetatype/EduGeyser) works out of the box. Students can always connect via direct IP. This extension only adds server list broadcasting, join codes, and the connection ID.

## Requirements

- [EduGeyser](https://github.com/SendableMetatype/EduGeyser) (Geyser fork with education support)
- Java 17+
- For server list: Global Admin access to an M365 Education tenant
- For join codes / connection ID: Any M365 Education account

## Setup

1. Download the latest release JAR from [Releases](https://github.com/SendableMetatype/Geyser-Education-Extension/releases)
2. Place it in Geyser's `extensions/` folder
3. Start the server once to generate config files
4. Configure as needed (see below)
5. Restart the server

## Join Codes and Connection ID

Join codes let students connect by entering symbols in Education Edition's join screen, or by clicking a share link. Join codes are tenant-scoped: each code only works for students in the same tenant as the account that created it.

The connection ID is a single 10-digit number shared across all tenants. Students can enter it directly in Education Edition to join from any tenant, bypassing join codes entirely.

### Quick Start

1. Run `/edu joincode add` from the console
2. Sign in with any M365 Education account when prompted
3. The join code, share link, and connection ID are printed to the console
4. Share with students:
    - **Join code link** - one-click join: `https://education.minecraft.net/joinworld/...`
    - **Connection ID** - works across any tenant

The active connection ID and all join codes are printed to the console every 3 minutes as a reminder.

### Multiple Tenants

Run `/edu joincode add` once per tenant. Each requires a separate education account sign-in. All tenants share the same connection ID - only the join codes are tenant-specific.

### Configuration

Edit `plugins/Geyser-*/extensions/edu/joincode_config.yml`:

```yaml
world-name: "My School Server"
host-name: "EduGeyser"
server-ip: ""            # Leave empty to auto-detect, or set to e.g. "mc.example.com"
                         # Port is always read from Geyser automatically.
connection-id: 1234567890  # Auto-generated on first run. Do NOT change to a predictable
                           # number - random 10-digit IDs avoid worldwide collisions.
max-players: 40
```

### Commands

| Command | Description |
|---------|-------------|
| `/edu joincode` | Show connection ID, active join codes, and share links |
| `/edu joincode add` | Create a join code for a new tenant |
| `/edu joincode remove <number>` | Remove a join code by its index |

### Notes

- Connection ID is **persistent** across restarts (stored in config)
- Join codes are **regenerated on every server restart** - the share link changes each time
- Codes stay alive via heartbeat while the server is running
- No Global Admin access required - any education account works

## Server List

Broadcasts your server to Education Edition's built-in server browser. Requires Global Admin access to each M365 Education tenant.

### Quick Start

1. Edit `plugins/Geyser-*/extensions/edu/serverlist_config.yml`:

```yaml
server-name: "My School Server"
server-ip: "mc.example.com"  # IP or hostname only. Port auto-detected from Geyser.
                             # Leave empty to auto-detect the public IP.
max-players: 40
```

2. Restart the server
3. Run `/edu serverlist add` from the console
4. Two device code prompts appear - sign in with a Global Admin M365 Education account
5. The server now appears in Education Edition's server list for that tenant

### Multiple Tenants

Run `/edu serverlist add` once per tenant. Each requires its own Global Admin account.

### Commands

| Command | Description |
|---------|-------------|
| `/edu serverlist` | Show all registered accounts with status |
| `/edu serverlist add` | Start device code flow for a new tenant |
| `/edu serverlist remove <number>` | Remove an account by its index |

## Files

| File | Purpose |
|------|---------|
| `joincode_config.yml` | World name, host name, server IP, **connection ID**, max players |
| `sessions_joincode.yml` | Join code OAuth tokens (managed automatically) |
| `serverlist_config.yml` | Server list name, IP, max players |
| `sessions_serverlist.yml` | Server list OAuth tokens (managed automatically) |

## Building

```
./gradlew build
```

The JAR is output to `build/libs/`. Includes native WebRTC libraries for all platforms (Windows/Linux/macOS, x86_64/aarch64).
