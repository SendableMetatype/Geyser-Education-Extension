# Geyser Education Extension

**Completely optional** [Geyser](https://geysermc.org/) extension that broadcasts your server to Minecraft Education Edition's built-in server list. When installed, students in your tenant see the server appear automatically in their server browser - no IP address, no resource pack, no link needed. They just open Education Edition and click Play.

**This extension is not required for education clients to connect.** Education support in [EduGeyser](https://github.com/SendableMetatype/EduGeyser) works out of the box with zero configuration. Students can always connect via direct IP or a connection link. This extension only adds the convenience of automatic server list broadcasting.

**Requires Global Admin access to an M365 Education tenant.** Only a tenant administrator can register servers in the Education Edition server list. If you are a teacher, student, or do not have Global Admin access, you do not need this extension - students can connect via direct IP instead.

## Requirements

- Geyser with education support (EduGeyser)
- Global Admin access to each M365 Education tenant you want to broadcast to
- Java 17+

## Setup

1. Download the latest release JAR
2. Place it in Geyser's `extensions/` folder
3. Start the server once to generate the config file
4. Edit `plugins/Geyser-*/extensions/edu/serverlist_config.yml`:

```yaml
server-name: "My School Server"
server-ip: "play.example.com:19132"
max-players: 40
```

5. Restart the server
6. Run `/edu serverlist add` from the console
7. Two device code prompts will appear - sign in with a Global Admin M365 Education account
8. The server now appears in Education Edition's server list for all students in that tenant

## Broadcasting to Multiple Tenants

Run `/edu serverlist add` once for each tenant. Each tenant requires its own Global Admin account. All accounts share the same server name, IP, and max player count from the config.

## Commands

| Command | Description |
|---------|-------------|
| `/edu serverlist` | Show all registered accounts with status |
| `/edu serverlist add` | Start device code flow for a new tenant |
| `/edu serverlist remove <number>` | Remove an account by its index |

## Files

| File | Purpose |
|------|---------|
| `serverlist_config.yml` | Server name, IP, and max players (edit this) |
| `sessions_serverlist.yml` | OAuth tokens and session data (managed automatically, do not edit) |

## How It Works

The extension authenticates with Microsoft's Education Server Services (MESS) using OAuth device code flows and registers the server via the MESS dedicated server API. Once registered, all Education Edition clients in the tenant see the server in their built-in server list automatically. The extension sends periodic heartbeats to keep the server listed as online and refreshes tokens automatically.

Each account requires two sign-ins: one for server management (tooling API) and one for server registration (education client API). Both use the same Global Admin account.

## Building

```
./gradlew build
```

The JAR is output to `build/libs/`.
