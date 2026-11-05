# Phase 5A — Verified Java/Bedrock Identity & Trust Design

Status: **PROPOSED / DESIGN ONLY — SECURITY AND OWNER SIGN-OFF REQUIRED**

Issue: [#42](https://github.com/thedusk4203/Minecraft_Server/issues/42). Parent readiness gate: [#41](https://github.com/thedusk4203/Minecraft_Server/issues/41). This design **does not** enable paid entitlements, install plugins, modify runtime identity records or authorize Phase 5B.

Review date: 2026-11-05. Base runtime: `main` `7b1eb0e52db8c9ac212a5f38edafdee872cb0ae8` (CI #215 SUCCESS).

## Goal and threat model

Authenticate the *underlying Java or Bedrock subject* before attaching any future commerce order or entitlement to the existing canonical `player_identity.player_id`. An unauthenticated name, apparent UUID, derived UUID pattern, web form, client plugin message or unauthenticated proxy connection **must never** create or move a paid entitlement. Existing free gameplay, COINS and encounter reward semantics remain unchanged.

Threat actors include:
- a player using a lookalike Java or Bedrock username;
- a forged Bedrock XUID/UUID or a Java-shaped UUID submitted by the client;
- a direct connection to Paper bypassing Velocity;
- a compromised or mismatched Floodgate forwarding key, proxy, or configuration;
- stale/replayed proxy claims, linked/unlinked accounts, concurrent joins and relogging;
- a buyer/seller trying to redirect a refund or supporter title to someone else's identity;
- an operator attempting unaudited manual reparenting of a paid entitlement.

### Existing repository facts

- `config/velocity/velocity.toml` sets `online-mode=true` and `player-info-forwarding-mode="modern"`; no deploy-time firewall or proxy trust proof is presently recorded.
- `compose.yaml` exposes Velocity at Java 25565 and Bedrock 19132, with Paper `ONLINE_MODE=FALSE` and `expose: 25565` on the internal Compose network. The Java-facing backend being unexposed in *this Compose configuration* is not evidence of every future production deployment.
- The proxy installs Geyser-Velocity and Floodgate-Velocity; the current `paper` service does not install Floodgate-Spigot.
- `PlayerBootstrapListener` calls `identities.recordSeen(minecraftUuid, playerName)` with no trusted-platform input. That overload defaults to `IdentityPlatform.UNKNOWN`.
- `PlayerIdentityRepository` upserts by `minecraft_uuid`, but its conflict clause only updates name/time; it **never** promotes or checks verified `platform` and `platform_subject`.
- V1 `player_identity` holds `player_id` and `minecraft_uuid` (unique); platform subject is nullable/nonunique. This is sufficient for current game progress but is **not** proof of payment-account control.
- Java/Bedrock authentications are especially ambiguous if a Bedrock account is linked to Java: Floodgate can use the Java UUID for the *Bedrock session*.

## Official source contracts (checked 2026-11-05)

1. [Floodgate API](https://geysermc.org/wiki/floodgate/api/): `FloodgateApi.getInstance()`, `isFloodgatePlayer(UUID)`, and `getPlayer(UUID)` allow querying a verified active Bedrock session in a correctly configured Floodgate plugin. The API explicitly requires `send-floodgate-data=true` and the *same* `key.pem` on proxy and backend when accessed on backend Paper.
2. [FloodgatePlayer API source](https://github.com/GeyserMC/Floodgate/blob/master/api/src/main/java/org/geysermc/floodgate/api/player/FloodgatePlayer.java): `getXuid()`, `isLinked()`, `getLinkedPlayer()`, `getJavaUniqueId()` and `getCorrectUniqueId()` are distinct concepts. `getCorrectUniqueId()` can reflect a linked Java UUID; do **not** confuse it with the Bedrock subject.
3. [Floodgate linking](https://geysermc.org/wiki/floodgate/linking/): globally linked Bedrock sessions may load Java player data and UUID, while unlinking changes visible player state.
4. [Floodgate proxy setup](https://geysermc.org/wiki/floodgate/setup/proxy-servers/): backend API integration requires proxy and backend Floodgate, secure `key.pem` provisioning and a verified forwarding setup. The key controls Bedrock access and must never be committed, printed or distributed externally.
5. [PaperMC Velocity forwarding](https://docs.papermc.io/velocity/player-information-forwarding/): modern forwarding uses an authenticated MAC and shared secret; Paper stays in offline mode **behind** the proxy.
6. [PaperMC backend security](https://docs.papermc.io/velocity/security/): modern forwarding **does not replace** network/firewall restrictions on direct backend connections.

Exact Floodgate/Paper API versions and capabilities must be revalidated against the versions installed during implementation. This document is not a substitute for a real authenticated Java-and-Bedrock staging test.

## Trust-boundary architecture

Proposed boundaries:

```text
Real Java account -- authenticated Velocity online-mode --+
                                                          |
Real Bedrock account -- Geyser + Floodgate at Velocity ---+--> modern forwarding --> Paper
                           [trusted proxy session]                              |
                             identity provenance                              v
                                                                              TRUSTED SESSION ADAPTER
                                                                              (not a client command)
                                                                                    |
                                                                      verified subject assertion
                                                                                    v
                                                                           binding registry (PG)
                                                                                    |
                                                                       canonical player_id mapping
                                                                                    v
                                                                  FUTURE commerce entitlement checks
```

Every session is either `VERIFIED_JAVA`, `VERIFIED_BEDROCK` or `UNVERIFIED`; absence of a Bedrock API result is **not independently proof** of Java authentication. A trusted complete proxy-attestation path and version/health check must exist before issuing a Java claim.

### Recommended v1 implementation path — backend Floodgate adapter (not yet approved)

1. Pin a compatible official Floodgate-Spigot artifact on Paper **only after** a security/config readiness PR. Configure proxy `send-floodgate-data=true`, forward via modern Velocity, and provision the required `key.pem` into each backend by secret management (never repository or CI artifacts).
2. Initialize a **separate optional adapter** after Floodgate plugin readiness. Query `FloodgateApi.isFloodgatePlayer(actualOnlineUuid)`, then `getPlayer(actualOnlineUuid)`; a positive Bedrock session uses the verified `FloodgatePlayer.getXuid()` as `(BEDROCK, xuid)`. Reject empty/malformed XUID and inconsistent/expired session metadata.
3. Record whether the Bedrock session `isLinked()` and the associated **distinct** Java link reference only for auditing. Neither `getCorrectUniqueId()` nor the current Bukkit UUID is the Bedrock XUID.
4. For authenticated Java players who are **positively** established as not a Floodgate session, use the forwarded, authenticated Java account UUID as `(JAVA, java_uuid)`. This classification is allowed only if the Floodgate backend is healthy and the Velocity authentication/forwarding path is attested; otherwise `UNVERIFIED`.
5. Fail closed for paid actions when proxy, Floodgate, key, backend or DB trust evidence is absent/mismatched. **Do not disconnect free players** solely because commerce verification is unavailable.
6. Test normal Java, unlinked Bedrock, globally linked Bedrock and Java accounts whose usernames collide. Require authentic Java and Bedrock logins in staging; offline Mineflayer E2E users can exercise negative/UNKNOWN paths but **cannot** establish real purchase identity.

**Alternative**: a signed short-lived proxy-to-backend identity attestation from a dedicated Velocity plugin. This would avoid distributing Floodgate decryption keys to each Paper server but needs its **own** formally reviewed signature, nonce, session binding, expiry, replay rejection, revocation, key rotation, firewall and error handling. Do not invent the wire protocol during Phase 5A.

### Crucial linked-account rule

Gameplay's effective Bukkit UUID may be a Java UUID when the current session is actually Bedrock. Preserve the existing `player_id` and gameplay associations, but never infer paid ownership from that shared UUID.

**Safe v1 decision proposal**: keep `(JAVA, uuid)` and `(BEDROCK, xuid)` as separate verified subjects. If a Bedrock session is globally linked to a Java account, quarantine any attempt to silently merge the paid-ownership subjects; do not purchase, gift, move or inherit entitlements across platforms until an explicitly verified account-linking policy is approved. Existing game inventory/progress may still follow Floodgate's own link semantics; commerce must not treat this as payment-account consent.

A Bedrock/XUID verified subject can have a distinct purchased cosmetic later only if a reviewed method securely binds that subject to the actual canonical `player_id` currently projected in game, handles link/unlink transitions, and forbids accidental cross-account display or reparenting. Until then: paid eligibility `UNVERIFIED/HELD`; free gameplay unchanged.

## Identity model — suggested future migration only (NO MIGRATION IN THIS PR)

Keep `player_identity.player_id` and all current foreign keys untouched. Do not use `player_identity.platform` or `platform_subject` as sole commerce authority.

Proposed **separate** `verified_subject_binding`:

| Column | Meaning |
| --- | --- |
| `binding_id UUID` | Stable immutable binding reference |
| `player_id UUID` | Existing canonical game identity; never reparent old game records |
| `platform` | Strict `JAVA` / `BEDROCK`, not UNKNOWN |
| `subject` | Canonical authenticated Java UUID or validated XUID with platform-specific normalization |
| `state` | `ACTIVE`, `QUARANTINED`, `RETIRED` |
| `trust_source` | Explicit adapter/source version and proxy trust domain, not client-reported text |
| `verified_at`, `last_confirmed_at` | Server-generated timestamps |
| `row_version` | Monotonic optimistic-concurrency version |
| `correlation_id` | Audit trail for the verifying session/decision |

Candidate constraints: platform-specific subject format, `UNIQUE(platform, subject)` for currently active claims (including quarantine policy), and a reviewed rule limiting simultaneous active subjects per player. Allow multi-subject-to-one-player **only through a separately approved linking workflow**. A subject already claimed by a different player must be quarantined, never last-writer-wins.

A future `verified_session_observation`/audit may record bounded proof metadata (`session_id`, `server_id`, provenance, timestamp, classification) without storing bearer tokens or `key.pem`. Keep sensitive values redacted and subject to retention rules.

**No automatic data migration by username.** Existing UNKNOWN rows stay playable; safe promotion should be an explicit state transition requiring current trusted subject proof and uniqueness checks. A linked player whose effective Bukkit UUID conflicts with an existing verified Java subject must enter a commerce quarantine, not silently claim that Java player's entitlements.

## State transitions

```text
UNVERIFIED
   ├─ trustworthy, unique subject proof ──> VERIFIED
   ├─ missing/broken proxy/Floodgate proof ──> UNVERIFIED
   └─ conflicting binding ──> QUARANTINED

VERIFIED
   ├─ repeat matching proof ──> VERIFIED (refresh last_confirmed_at)
   ├─ claim mismatch or identity collision ──> QUARANTINED
   └─ reviewed retirement/unlink policy ──> RETIRED

QUARANTINED -- security review + independent proof --> VERIFIED or RETIRED
```

`VERIFIED` applies to the **binding record**, not a permanent guarantee that all future sessions are trusted. Every paid-action authorization requires a fresh trusted active-session or secure purchase-site authentication and matching binding; a stale verified binding alone must not authorize a transfer or purchase.

## Future implementation split (approval required)

- **I1 — Trust/topology review (docs + staging tests):** audit modern forwarding and firewall, Floodgate topology and version pin; choose backend API versus proxy attestation.
- **I2 — Schema/state machine design:** standalone binding table, uniqueness, audit, quarantine and safe UNKNOWN promotion; preserve Phase 1–4D.
- **I3 — Trusted adapter only:** verified session claim interface with Java/Bedrock integration and fail-closed payment eligibility; no real commerce.
- **I4 — Migration/reconciliation tests:** concurrent claims, collisions and controlled linked/unlinked behavior.
- **I5 — Link/purchase authorization approval:** separate security/product decision before attaching any paid entitlement.

Do not bundle proxy authentication changes, a V10 migration and a payment gateway into one PR.

## Deterministic acceptance matrix (future test definitions)

| ID | Scenario | Required outcome |
| --- | --- | --- |
| ID01 | Authenticated Java through trusted Velocity, healthy adapter | `VERIFIED_JAVA`, canonical Java UUID |
| ID02 | Unlinked Bedrock through healthy Floodgate | `VERIFIED_BEDROCK`, underlying XUID |
| ID03 | Globally linked Bedrock whose effective Bukkit UUID is Java | Classified BEDROCK using XUID; paid cross-identity entitlement blocked pending link approval |
| ID04 | Java/Bedrock same display name or prefix | Distinct platform+subject; no merge |
| ID05 | Rename same trusted Java account | Binding unchanged |
| ID06 | Rename same Bedrock/XUID account | Binding unchanged |
| ID07 | UNKNOWN historical row with trustworthy unique proof | Explicit reviewed promotion without changing `player_id` |
| ID08 | Existing active subject bound to different player | Atomic conflict/quarantine, no overwrite |
| ID09 | Concurrent verification attempts for same subject | Exactly one active owner |
| ID10 | Floodgate plugin absent or unhealthy | Paid actions fail closed, free gameplay works |
| ID11 | Wrong/missing forwarding secret or shared Floodgate key | No trusted proof, no paid grant; normal security policies apply |
| ID12 | Direct Paper port attempt / spoofed UUID or XUID | Cannot impersonate authenticated subject |
| ID13 | Replayed/stale session claim after disconnect or restart | Rejected; no entitlement transfer |
| ID14 | Global link or unlink changes effective UUID | No accidental paid-ownership migration |
| ID15 | Existing market/COINS/encounter/reward rows | Unmodified after identity migration/reconciliation |
| ID16 | CI offline Mineflayer session | Stays UNVERIFIED for commerce; retains game-only E2E gameplay |

Test the positive identity paths with real authenticated Java **and** Bedrock clients in a dedicated staging integration gate; the current offline test bot suite is insufficient to certify this contract.

## Explicit readiness decision

- Phase 4D remains **FROZEN**.
- Issue #42 stays **OPEN** pending technical/security review and a tested identity implementation plan.
- Issue #41 stays **OPEN** pending product/policy/identity/payment decisions.
- No change to production secrets, Floodgate topology, PlayerBootstrapListener, database or entitlements in this PR.
- **No Phase 5B implementation or live checkout is authorized.**
