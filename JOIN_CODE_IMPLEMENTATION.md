# Join Code System for EduGeyser — Technical Design Document

## 1. Executive Summary

This document covers the complete technical path for adding Education Edition join code support to EduGeyser. The join code system allows students to enter a sequence of symbols (Book, Balloon, Rail, etc.) to connect to a server, instead of typing an IP address or finding it in the server list.

The join code system is built on top of two Microsoft services — **Discovery** and **Nethernet** — that are architecturally separate from the MESS (Minecraft Education Server Service) dedicated server system that EduGeyser already uses. The two systems can run in parallel: MESS provides a permanent server list entry, while Discovery provides a shareable join code.

**The core finding:** MCXboxBroadcast already implements a working "Nethernet micro-server that redirects to a real server via TransferPacket" pattern. The join code implementation is essentially the same pattern with the signaling/discovery layer swapped from Xbox Live to Education Discovery.

---

## 2. How the Join Code System Works

### 2.1 Overview

The join code system does **not** use IP:port connections. It is built entirely on Nethernet (WebRTC). The flow is:

1. **Server** registers with Discovery API → receives a **passcode** (join code) and uses a **nethernetID**
2. **Student** enters join code symbols in-game → client calls Discovery `/joininfo` → receives the server's **nethernetID**
3. **Student's client** opens a WebSocket to the signaling server and initiates a **WebRTC peer connection** to the server's nethernetID
4. **Server** accepts the WebRTC connection, runs a minimal Bedrock handshake, and sends a **TransferPacket** pointing to the real Geyser RakNet IP:port
5. **Student's client** disconnects from the Nethernet connection and reconnects via standard **RakNet** to Geyser

### 2.2 Discovery API

Base URL: `https://discovery.minecrafteduservices.com`

All endpoints require `User-Agent: libhttpclient/1.0.0.0`. Authentication is via MS access token (Bearer) or server token, depending on the endpoint.

#### `/host` — Register a world and get a join code

**Auth:** MS access token (Bearer header, since v1.21.90 api-version 2.0)

```json
// Request
{
    "build": 12110000,
    "locale": "en_US",
    "maxPlayers": 40,
    "networkId": "<nethernetID as string>",
    "playerCount": 1,
    "protocolVersion": 1,
    "serverDetails": "Username",
    "serverName": "My World",
    "transportType": 2
}

// Response
{
    "serverToken": "tenantID|userID|UTCTimestamp|256charHex",
    "passcode": "10,13,5,1,17"
}
```

Key points:
- `transportType: 2` = Nethernet. This is the only option for join codes.
- `networkId` is a random uint64 (first digit nonzero), same as the signaling WebSocket ID.
- The passcode is a comma-separated list of symbol indices. You **cannot** choose your code — it is server-assigned.
- The serverToken format is `tenantID|userID|UTCTimestamp|256charHex`. The timestamp is the expiry (10 days from creation).
- Join codes are **organization-scoped**: a code only works for students in the same tenant.

#### `/joininfo` — Resolve a join code to a nethernetID

**Auth:** MS access token (Bearer)

```json
// Request
{ "accessToken": "<MSFT Access Token>", "build": 12110000, "locale": "en_US", "passcode": "10,13,5,1,17", "protocolVersion": 1 }

// Response
{ "connectionInfo": { "info": { "id": "<nethernetID>" }, "transportType": 2 }, "serverDetails": "Username", "serverName": "My World" }
```

This is what the student's client calls after entering symbols. It returns a nethernetID, **not** an IP:port.

#### `/heartbeat` — Keep the join code alive

**Auth:** Server token (Bearer)

```json
{ "build": 12110000, "locale": "en_US", "passcode": "10,13,5,1,17", "protocolVersion": 1, "transportType": 2 }
```

Must be sent periodically (default every 100 seconds, configurable via EduToken `discovery.heartbeatFrequencyS`). Failure to heartbeat will eventually expire the join code from Discovery.

**Open question:** How long is the grace period if heartbeats stop (e.g., during a server restart)? The EduToken contains `maxRetryCount: 3`, suggesting at least ~5 minutes of tolerance. This needs empirical testing.

#### `/update` — Update world metadata

**Auth:** Server token (Bearer)

Same fields as `/host` minus `accessToken` and `networkId`, plus `passcode` and `serverToken`. Returns empty body. Required to be sent when a player connects via `CONNECTREQUEST`.

#### `/dehost` — Remove the join code

**Auth:** Server token (Bearer)

Same request as heartbeat. Destroys the passcode. The Nethernet signaling WebSocket can continue running independently.

#### Change Join Code

Dehost then immediately host again. This is how the game does it.

#### Share Links

Base64-encode the passcode string:
- Indirect: `https://education.minecraft.net/joinworld/{base64}`
- Direct: `minecraftedu://?joinworld={base64}`

The link becomes invalid when the join code changes.

### 2.3 Join Code Symbols

There are 18 known symbols, mapped by index:

| Index | Symbol | Index | Symbol | Index | Symbol |
|-------|--------|-------|--------|-------|--------|
| 0 | Book | 6 | Agent | 12 | Carrot |
| 1 | Balloon | 7 | Cake | 13 | Panda |
| 2 | Rail | 8 | Pickaxe | 14 | Sign |
| 3 | Alex | 9 | WaterBucket | 15 | Potion |
| 4 | Cookie | 10 | Steve | 16 | Map |
| 5 | Fish | 11 | Apple | 17 | Llama |

Join codes can be 4, 5, or 6 symbols long (configurable via EduToken `discovery.joinCodeLength`, default 4).

### 2.4 Nethernet / Signaling

Nethernet is a WebRTC-based transport layer. For the join code system, it works as follows:

1. **Server** opens a WebSocket to `wss://signal.franchise.minecraft-services.net/ws/v1.0/signaling/{nethernetID}` authenticated with an MCToken
2. The signaling server immediately sends back **STUN/TURN credentials**
3. When a student resolves the join code via `/joininfo` and gets the server's nethernetID, the student's client also connects to the signaling server (with their own nethernetID) and sends a `CONNECTREQUEST` message containing an SDP offer
4. The server receives the `CONNECTREQUEST`, responds with a `CONNECTRESPONSE` (SDP answer), and they exchange ICE candidates via `CANDIDATEADD` messages
5. WebRTC peer connection is established with two data channels: `ReliableDataChannel` and `UnreliableDataChannel`
6. Bedrock protocol packets flow over these data channels

Signaling message format: `MESSAGETYPE CONNECTIONID DATA`

Message types: `CONNECTREQUEST`, `CONNECTRESPONSE`, `CANDIDATEADD`, `CONNECTERROR`

### 2.5 Server Token Lifecycle

- **Format:** `tenantID|userID|UTCTimestamp|256charHex`
- **Lifetime:** 10 days (confirmed by examining a real token: created April 10, expires April 20)
- **No refresh endpoint exists** in the Discovery API. After expiry, you must call `/host` again, which generates a new join code.
- **Persistence strategy:** Store the serverToken + passcode to disk. On restart, skip `/host` and resume heartbeating with the stored values. Only call `/host` again when the token expires (day 9 recommended).
- **The earlier GeyserEducationSupport extension used `SIGNED_TOKEN_LIFETIME = 604800` (7 days) — this was incorrect. Actual lifetime is 864000 seconds (10 days).**

---

## 3. Architecture: The Proxy Shell Approach

### 3.1 Why Not Native Nethernet?

Geyser is a RakNet server. Integrating Nethernet as a full transport layer would mean accepting WebRTC connections, unwrapping Bedrock packets from data channels, and maintaining WebRTC peer connections for every player for the entire session. This is a deep, invasive change to Geyser's network stack.

### 3.2 The TransferPacket Approach

Instead, run a lightweight "micro-server" that:
1. Accepts incoming Nethernet (WebRTC) connections
2. Runs a minimal Bedrock handshake
3. Sends a `TransferPacket` pointing to Geyser's RakNet IP:port
4. Tears down the Nethernet connection

The student's client then reconnects via standard RakNet. This works because Education Edition clients **can** connect via RakNet to an IP — the MESS dedicated server system does exactly this.

### 3.3 Existing Implementation: MCXboxBroadcast

MCXboxBroadcast already implements this exact pattern for Xbox Live. The key files are:

**`SessionManagerCore.setupNetherNet()`** — Server bootstrap:
```java
long netherNetId = this.sessionInfo.getNetherNetId().longValue();
this.signaling = new NetherNetXboxSignaling(netherNetId, getMCTokenHeader());

ServerBootstrap b = new ServerBootstrap();
b.group(bossGroup, workerGroup)
    .channelFactory(NetherNetChannelFactory.server(new PeerConnectionFactory(), signaling))
    .childHandler(new BroadcasterChannelInitializer(sessionInfo, this, logger));

this.netherNetChannel = b.bind(new InetSocketAddress(0)).sync().channel();
```

**`RedirectPacketHandler`** — Minimal Bedrock handshake + transfer:
```
RequestNetworkSettings → NetworkSettings (compression)
LoginPacket → PlayStatus(LOGIN_SUCCESS) → ResourcePacksInfo
ResourcePackClientResponse(HAVE_ALL_PACKS) → ResourcePackStack
ResourcePackClientResponse(COMPLETED) → StartGamePacket → TransferPacket
```

No chunks, no inventory, no `PlayStatus(PLAYER_SPAWN)`. The client accepts the transfer immediately after `StartGamePacket`. This is confirmed working in production.

**Dependencies used:**
- `dev.kastle.netty:netty-transport-nethernet:1.7.0` — Netty channel abstraction for Nethernet/WebRTC
- `dev.kastle.webrtc:webrtc-java:1.0.3` — Native WebRTC bindings (platform-specific: windows-x86_64, linux-x86_64, macos-x86_64, etc.)
- These are authored by Kas-tle (GeyserMC contributor) and hosted on `repo.viaversion.com`

### 3.4 What Changes for Education Join Codes

The only difference from MCXboxBroadcast is the **signaling and discovery layer**:

| Component | MCXboxBroadcast (Xbox) | Join Code (Education) |
|-----------|----------------------|----------------------|
| Discovery | Xbox Live Session Directory | Discovery API (`/host`, `/heartbeat`, etc.) |
| Signaling | `NetherNetXboxSignaling` (Xbox MCToken) | Same WebSocket, but authenticated with Education MCToken |
| Nethernet server | `NetherNetChannelFactory.server(...)` | **Identical** |
| Redirect handler | `RedirectPacketHandler` | **Identical** |
| Transfer target | Config IP:port | Geyser's RakNet IP:port (localhost or public) |

---

## 4. Authentication & Token Sharing

### 4.1 Does the join code system need the server's IP?

**No.** The Discovery `/host` endpoint takes a `networkId` (nethernetID) and `transportType: 2` (Nethernet). It does not take an IP. The join code resolves to a nethernetID, not an IP. The IP only enters the picture in the `TransferPacket` that redirects the student to Geyser's RakNet listener.

### 4.2 Token Sharing with MESS

The join code system and the MESS server list system use **overlapping but not identical** auth:

| Token | MESS (Server List) | Discovery (Join Codes) |
|-------|-------------------|----------------------|
| MS Access Token (client ID `b36b1432-...`) | Used for `/server/register`, `/server/fetch_token` | Used for `/host`, `/joininfo` (since v1.21.90) |
| MCToken (via PlayFab) | Used for MESS `/server/host`, `/server/update` | Used for signaling WebSocket auth |
| Server Token (pipe-separated) | From MESS `/server/fetch_token` (refreshable) | From Discovery `/host` (10-day expiry, not refreshable) |
| Tooling Token (client ID `1c91b067-...`) | Used for `/tooling/edit_tenant_settings`, `/tooling/edit_server_info` | **Not needed** |

**Recommendation:** The join code system should be integrated into `EducationAuthManager`, not a separate extension. The MS access token and MCToken are already obtained there. Adding Discovery API calls is natural — they share the same OAuth session. The server token from Discovery is separate from the MESS server token and should be stored alongside it in `edu_official.yml`.

### 4.3 Integration vs Separation

**Integrated approach (recommended):**
- Add Discovery API methods to `EducationAuthManager` (`hostDiscovery()`, `heartbeatDiscovery()`, `dehostDiscovery()`)
- Store Discovery serverToken + passcode in `edu_official.yml` alongside MESS session data
- Add Nethernet server bootstrap to `GeyserServer` or a new `DiscoveryNetherNetManager`
- Reuse the existing `RedirectPacketHandler` pattern from MCXboxBroadcast
- Add join code display to `/geyser edu status`
- Share the MS access token and MCToken with MESS auth

**Why not a separate extension:**
- The OAuth tokens are already managed by `EducationAuthManager`
- The Nethernet server needs MCToken for signaling auth, which is deep in the auth flow
- Join code lifecycle (heartbeat, dehost on shutdown) needs to be tightly coupled with server lifecycle
- Sharing the nethernetID between Discovery and the Nethernet listener requires coordination

### 4.4 Can Both Systems Run Simultaneously?

**Yes.** They use different APIs, different endpoints, and different transport types. A server can:
- Be registered with MESS (`transportType: 0`, direct IP) → students see it in the server list permanently
- Be registered with Discovery (`transportType: 2`, Nethernet) → students can also use a join code

Students who use the server list connect via RakNet directly. Students who use the join code connect via Nethernet → TransferPacket → RakNet.

---

## 5. Implementation Plan

### 5.1 Components

#### A. Discovery API Client

New class: `DiscoveryManager` (or methods on `EducationAuthManager`)

- `hostDiscovery(nethernetID, worldParams)` → stores serverToken + passcode
- `heartbeatDiscovery()` → called every 100 seconds via scheduled task
- `updateDiscovery(playerCount, maxPlayers)` → called on CONNECTREQUEST
- `dehostDiscovery()` → called on shutdown
- Persistence: save/restore serverToken + passcode to disk for restart resilience

#### B. Nethernet Micro-Server

New class: `JoinCodeNetherNetServer`

- Uses `NetherNetChannelFactory.server(new PeerConnectionFactory(), signaling)` (from kastle library)
- Signaling: create a new signaling class or adapt `NetherNetXboxSignaling` for Education MCToken auth
- Channel initializer: reuse MCXboxBroadcast's `BroadcasterChannelInitializer` pattern
- Redirect handler: reuse `RedirectPacketHandler` (StartGamePacket → TransferPacket to Geyser RakNet port)

#### C. Signaling Adapter

The signaling WebSocket URL is the same: `wss://signal.franchise.minecraft-services.net/ws/v1.0/signaling/{nethernetID}`

The kastle library's `NetherNetXboxSignaling` authenticates with an MCToken. Education Edition uses the same MCToken format (obtained via PlayFab → session/start). The signaling class may work as-is, or may need minor adaptation if the MCToken treatment overrides differ between Xbox and Education.

#### D. Configuration

Add to `GeyserConfig.EducationConfig`:
```yaml
education:
  tenancy-mode: "official"
  join-code:
    enabled: true
    transfer-address: ""  # empty = auto-detect (same as MESS server-ip logic)
    transfer-port: 19132  # Geyser's RakNet port
```

#### E. Commands

Extend `/geyser edu status`:
```
Join Code: Book, Balloon, Rail, Alex (passcode: 0,1,2,3)
Share Link: https://education.minecraft.net/joinworld/MCwxLDIsMw==
Join Code Expires: 2026-04-20 13:22:52 UTC (8 days remaining)
Nethernet ID: 1234567890123456789
```

### 5.2 Dependencies to Add

```gradle
// In edugeyser build.gradle
implementation("dev.kastle.netty:netty-transport-nethernet:1.7.0") {
    exclude group: "io.netty"
}
["windows-x86_64", "linux-x86_64", "linux-aarch64", "macos-x86_64", "macos-aarch64"].each {
    implementation "dev.kastle.webrtc:webrtc-java:1.0.3:$it"
}
```

Repository: `https://repo.viaversion.com`

### 5.3 Startup Sequence

```
1. EducationAuthManager.initialize()
   ├── Obtain MS access token (existing)
   ├── Obtain MCToken (existing)
   ├── Register with MESS if official/hybrid (existing)
   └── If join-code.enabled:
       ├── Load saved Discovery session (serverToken + passcode)
       ├── If saved session exists AND token not expired:
       │   ├── Try heartbeat with saved credentials
       │   ├── If heartbeat succeeds: resume with saved join code ✓
       │   └── If heartbeat fails: fall through to /host
       ├── If no saved session OR token expired:
       │   ├── Generate random nethernetID
       │   ├── Call /host → get serverToken + passcode
       │   └── Save to disk
       ├── Start Nethernet micro-server (signaling + WebRTC listener)
       ├── Schedule heartbeat every 100 seconds
       ├── Schedule token expiry check (rehost on day 9)
       └── Log join code + share link
```

### 5.4 Shutdown Sequence

```
1. Cancel heartbeat task
2. Call /dehost (best-effort, don't fail shutdown on error)
3. Close Nethernet channel
4. Close signaling WebSocket
5. Save session data (for potential restart resume)
```

---

## 6. Open Questions

### 6.1 Heartbeat Grace Period

**Question:** If the server stops heartbeating (e.g., during a 5-minute restart), does Discovery expire the join code?

**How to test:** Host a world via mcedu Python library, stop heartbeating, wait N minutes, try heartbeating again. If it accepts, the code survived. Test at 2, 5, 10, 30 minutes.

**Impact:** If the grace period is short, restarts will always generate a new join code. If generous (>5 min), the resume-on-restart strategy works.

### 6.2 Signaling Auth Compatibility

**Question:** Does the kastle `NetherNetXboxSignaling` class work with Education MCTokens, or does it need modification?

**How to test:** Use an Education MCToken with the existing signaling class and see if the WebSocket accepts the connection.

**Impact:** If it works, no new signaling code needed. If not, a new `NetherNetEduSignaling` class is needed (likely minimal changes — same WebSocket URL, different token source).

### 6.3 Join Code Determinism

**Question:** Is the join code deterministic based on nethernetID, or randomly assigned?

**How to test:** Host with a fixed nethernetID, record the code, dehost, rehost with the same ID, compare codes.

**Impact:** If deterministic, the join code would be stable even after token expiry (rehost with same nethernetID = same code). If random, codes change every 10 days minimum.

### 6.4 Cross-Tenant Join Codes

**Question:** Can a join code from tenant A be used by a student in tenant B?

**Expected answer:** No. The docs say "Join codes you make only work in your organization." This matches the MESS behavior. Multi-tenant servers would need multiple Discovery sessions with multiple join codes.

---

## 7. Reference Implementations

| Project | What it provides | Location |
|---------|-----------------|----------|
| **MCXboxBroadcast** | Complete Nethernet micro-server + TransferPacket redirect pattern | `/home/claude/Broadcaster/` |
| **mcedu** (Python) | Working Discovery API client, auth flows, join code parsing | `/home/claude/join-code-resources/mcedu/` |
| **josef240/mcedu-docs** | Complete Discovery/signaling/edutoken/startup documentation | `/home/claude/join-code-resources/docs/` |
| **edugeyser** | EducationAuthManager with MESS registration, multi-tenancy, token management | `/home/claude/edugeyser/` |
| **GeyserEducationSupport** | Earlier standalone per-tenant token management extension | `/home/claude/GeyserEducationSupport/` |
| **nethernet-spec (lactyy)** | Updated Nethernet protocol spec (v1.21.20) | `/home/claude/join-code-resources/nethernet-spec-lactyy/` |
| **ViaProxy** | Client-side Nethernet usage with kastle libraries | `/home/claude/join-code-resources/ViaProxy/` |

---

## 8. Summary

The join code system is feasible and the hardest parts have existing implementations. The core insight is that the Nethernet connection only needs to survive long enough to send a TransferPacket — MCXboxBroadcast already proves this works in production. The Discovery API is straightforward HTTP. The main engineering work is:

1. **Glue:** Wire Discovery API calls into EducationAuthManager (~200 lines)
2. **Glue:** Start a Nethernet micro-server using the kastle library (~50 lines, modeled on MCXboxBroadcast)
3. **Adapt:** Create or adapt a signaling class for Education MCToken auth (~50 lines if modification needed)
4. **Reuse:** RedirectPacketHandler from MCXboxBroadcast (copy as-is or with minimal education-specific tweaks)
5. **Config/UX:** Join code display, share link generation, persistence across restarts (~100 lines)

Total estimated new code: ~400-500 lines, with most of the complexity handled by existing libraries.
