# Geyser Education Extension

Optional [Geyser](https://geysermc.org/) extension that registers your server in Minecraft Education Edition's built-in server list. Students can find and connect to the server directly from the in-game server browser without entering an IP address.

This extension is for servers already running [EduGeyser](https://github.com/SendableMetatype/EduGeyser) (or Geyser with education support). Education clients can connect without this extension - it only adds server list visibility.

## Requirements

- Geyser with education support (EduGeyser)
- A Global Admin account for each M365 Education tenant you want the server listed on
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
7. Two device code prompts will appear - sign in with a Global Admin M365 Education account for each
8. The server is now visible in Education Edition's server list for that tenant

## Adding Multiple Tenants

Run `/edu serverlist add` again for each additional school. Each tenant needs its own Global Admin account. All accounts share the same server name, IP, and max player count from the config.

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

The extension authenticates with Microsoft's Education Server Services (MESS) using OAuth device code flows. It registers the server via the MESS dedicated server API, configures tenant settings, and sends periodic heartbeats to keep the server listed as online. Token refresh happens automatically every 30 minutes.

Each account requires two sign-ins: one for server management (tooling API) and one for server registration (education client API). Both use the same Global Admin account.

## Building

```
./gradlew build
```

The JAR is output to `build/libs/`.
