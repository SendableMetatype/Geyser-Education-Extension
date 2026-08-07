# Geyser Education Extension

> [!IMPORTANT]
> If you are an end user of EduGeyser and looking to set it up, this repo has nothing of value to you. Get EduGeyser from the [download page](https://edugeyser.org/download) instead, and follow the [setup guide](https://edugeyser.org/wiki/geyser/education/setup) on the website.

This is the source code of the education extension that ships bundled inside [EduGeyser](https://edugeyser.org/). It installs itself automatically on startup; there is nothing to download and nothing to place in the extensions folder. It handles how Minecraft Education Edition students connect to a server:

1. **Connection ID**: a stable ID that students enter in Education Edition's connection dialog. Works across all tenants, no accounts needed.
2. **Join Codes**: codes that students enter on Education Edition's join code screen, or open through a share link. Each code is tied to one M365 Education tenant.
3. **Server List**: broadcasts the server to Education Edition's own server browser. Requires Global Admin access to an M365 Education tenant.
4. **Tenant Whitelist**: optionally restricts which organizations may join.

Full user documentation lives on the website: [edugeyser.org/wiki/geyser/education/connection-methods](https://edugeyser.org/wiki/geyser/education/connection-methods)

## Connection ID

The connection ID is generated and owned by EduGeyser itself, not by this extension. It is stored in `nethernet/connection-id.yml` inside the Geyser folder, persists across restarts, and is printed to the console on every startup:

```
[Nethernet] Listening on connection ID: 1234567890123456785daffd38c5bbac52ba313d53d9354b58
```

The ID is a number stored in the file followed by an account identifier that EduGeyser stores alongside it, so the full value is stable across restarts. Generated numbers are 18 digits; edited values may be 10 to 18 digits. Delete the file to generate a fresh one, which regenerates both halves. Keep the number random, predictable values invite collisions with other servers.

Students connect by opening Education Edition, pressing **Play**, then **Join World**, then the small **...** button to the right of the confirm button. In this dialog they can enter the connection ID to join.

## Join Codes (Optional)

Join codes let students connect by entering symbols on Education Edition's join screen, or by clicking a share link. Each code only works for students in the same tenant as the account that created it.

### Quick Start

1. Run `/edu joincode add` from the console
2. Sign in with any M365 Education account when prompted
3. The join code, share link, and connection ID are printed to the console
4. Share with students:
    * **Join code link** for joining with one click: `https://education.minecraft.net/joinworld/...`
    * **Connection ID**, which works across any tenant

The connection ID, along with any active join codes and their share links, is printed to the console every 15 minutes as a reminder.

### Multiple Tenants

Run `/edu joincode add` once per tenant. Each requires a separate education account sign in. All tenants share the same connection ID; only the join codes are per tenant.

### Configuration

Edit `extensions/edu/joincode_config.yml` inside the Geyser folder:

```yaml
world-name: "World Name"
host-name: "Server Name"
```

Configure and enforce player limits in the backend server software. Join-code Discovery registration does not provide an authoritative player cap.

### Commands

| Command | Description |
|---------|-------------|
| `/edu joincode` | Show the connection ID, active join codes, and share links |
| `/edu joincode add` | Create a join code for a new tenant |
| `/edu joincode remove <number>` | Remove a join code by its index |
| `/edu joincode rebuild` | Force a rebuild of the signaling connection |

### Notes

* The connection ID is persistent across restarts (stored by EduGeyser in `nethernet/connection-id.yml`)
* Join codes and share links are restored across restarts while their saved registration remains active; expired registrations are replaced automatically
* Codes stay alive via heartbeat while the server is running
* No Global Admin access required, any education account works

## Server List (Optional)

Broadcasts your server to Education Edition's own server browser. Requires Global Admin access to each M365 Education tenant.

### Quick Start

1. Edit `extensions/edu/serverlist_config.yml`:

```yaml
server-name: "My School Server"
server-ip: "mc.example.com"  # Your public IP or hostname that students connect to.
server-port: "19132"         # The external port students connect to.
max-players: 40
```

> **Always set `server-ip` and `server-port` explicitly.** Automatic detection is best effort and will cause issues behind NAT, tunnels, and reverse proxies, or when the external port differs from Geyser's bind port.

2. Restart the server
3. Run `/edu serverlist add` from the console
4. Two device code prompts appear, sign in with a Global Admin M365 Education account
5. The server now appears in Education Edition's server list for that tenant

### Multiple Tenants

Run `/edu serverlist add` once per tenant. Each requires its own Global Admin account.

### Commands

| Command | Description |
|---------|-------------|
| `/edu serverlist` | Show all registered accounts with status |
| `/edu serverlist add` | Start the device code flow for a new tenant |
| `/edu serverlist remove <number>` | Remove an account by its index |

## Tenant Whitelist (Optional)

Restricts which organizations (Microsoft Entra tenants) are allowed to join. Edit `extensions/edu/tenant_whitelist.yml`:

```yaml
enabled: true
tenants:
  - "03b5e7a1-cb09-4417-9e1a-c686b440b2c5"
```

* `enabled` is the master switch. Set it to `false` to turn the whitelist off without deleting your list.
* While enabled, an empty list allows everyone and a filled list allows only the listed tenants.

This is an advanced feature. A wrong tenant list stops legitimate students from joining, so leave the list empty unless you specifically need it.

## Files

All paths are relative to the Geyser folder, which depends on your platform: `plugins/Geyser-Spigot/` on Paper and Spigot, `plugins/geyser/` on Velocity, `config/Geyser-Fabric/` on Fabric, or the working directory for standalone.

| File | Purpose |
|------|---------|
| `nethernet/connection-id.yml` | The connection ID and signaling account identity, owned by EduGeyser |
| `extensions/edu/joincode_config.yml` | World and host names shown for join codes |
| `extensions/edu/sessions_joincode.yml` | Join code OAuth tokens (managed automatically) |
| `extensions/edu/serverlist_config.yml` | Server list name, IP, port, max players |
| `extensions/edu/sessions_serverlist.yml` | Server list OAuth tokens (managed automatically) |
| `extensions/edu/tenant_whitelist.yml` | Allowed tenants |

## Building

```
./gradlew build
```

The JAR is output to `build/libs/`. It compiles against the EduGeyser API through the composite build in `settings.gradle.kts`, which substitutes the api and core dependencies with a sibling [EduGeyser](https://codeberg.org/SendableMetatype/EduGeyser) checkout at `../EduGeyser` built from source. Keep that checkout present and current. The WebRTC transport and its native libraries live in EduGeyser itself.
