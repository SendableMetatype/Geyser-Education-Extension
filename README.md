# Geyser Education Extension

Required [EduGeyser](https://github.com/SendableMetatype/EduGeyser) extension that handles how Minecraft Education Edition students connect to your server:

1. **Connection ID** - A 10-digit number that students enter in Education Edition's connection dialog. Works across all tenants. Generated automatically on first start, no accounts needed.
2. **Join Codes** - Tenant-scoped codes that students enter in Education Edition's "Join Code" screen, or click a share link. Requires an M365 Education account.
3. **Server List** - Broadcasts your server to Education Edition's built-in server browser. Requires Global Admin access to an M365 Education tenant.

## Requirements

- [EduGeyser](https://github.com/SendableMetatype/EduGeyser) (Geyser fork with education support)
- Java 17+

## Setup

1. Download the latest release JAR from [Releases](https://github.com/SendableMetatype/Geyser-Education-Extension/releases)
2. Place it in Geyser's `extensions/` folder
3. Start the server

On first start, the extension generates a **Connection ID** and prints it to the console. Students connect by opening Education Edition, pressing **Play**, then **Join World**, then the small **...** button to the right of the confirm button. In this dialog they can enter the Connection ID to join.

That's it for basic setup. No accounts or configuration needed.

## Join Codes (Optional)

Join codes let students connect by entering symbols in Education Edition's join screen, or by clicking a share link. Join codes are tenant-scoped: each code only works for students in the same tenant as the account that created it.

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
connection-id: "1234567890"  # Auto-generated on first run. Do NOT change to a predictable
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
- Join codes and share links **change on every server restart**
- Codes stay alive via heartbeat while the server is running
- No Global Admin access required - any education account works

## Server List (Optional)

Broadcasts your server to Education Edition's built-in server browser. Requires Global Admin access to each M365 Education tenant.

### Quick Start

1. Edit `plugins/Geyser-*/extensions/edu/serverlist_config.yml`:

```yaml
server-name: "My School Server"
server-ip: "mc.example.com"  # Your public IP or hostname that students connect to.
server-port: "19132"         # The external port students connect to.
max-players: 40
```

> **Always set `server-ip` and `server-port` explicitly.** Auto-detection is best-effort and will cause issues behind NAT, tunnels, reverse proxies, or when the external port differs from Geyser's bind port.

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
| `joincode_config.yml` | World name, host name, **connection ID**, max players |
| `sessions_joincode.yml` | Join code OAuth tokens (managed automatically) |
| `serverlist_config.yml` | Server list name, IP, port, max players |
| `sessions_serverlist.yml` | Server list OAuth tokens (managed automatically) |

## Building

```
./gradlew build
```

The JAR is output to `build/libs/`. Includes native WebRTC libraries for all platforms (Windows/Linux/macOS, x86_64/aarch64).
