# Phase 4B Freeze — First Boss Combat Vertical Slice

Status: **FROZEN**

Frozen base merge: `781a7252c57829d5e2da7daeee8bf0ea6cef7f4e`

## Scope frozen

Phase 4B establishes the first real boss-combat vertical slice on top of the frozen Phase 4A encounter runtime.

Frozen behavior:
- one validated `stone_warden` encounter definition;
- 1–4 bound participants per durable encounter run;
- server-owned Iron Golem boss spawn/despawn lifecycle;
- party-size health scaling;
- Seismic Slam and Stone Charge mechanics with readable telegraphs;
- low-health enrage timing;
- horizontal and vertical arena/anti-cheese boundaries;
- non-participant and invalid-source boss damage rejection;
- participant-loss, wipe, timeout, spawn-failure and boss-death terminalization;
- idempotent cleanup around the durable `encounter_run` lifecycle;
- structured boss telemetry;
- no loot, item or currency reward mutation in the combat slice.

## Verification receipts

### Implementation receipt

PR #13 — `Phase 4B: Stone Warden combat vertical slice`

- merged at `014bf43779e12456c8f211d760d696411f097397`;
- final implementation head `fd882c79457898a9546ed956a6a60ca8d91d6336`;
- CI `41025886647` — **SUCCESS**;
- build/unit/resource packaging/PostgreSQL/full Paper stack/market/dependency/crossplay regression gates passed.

### Real-client gameplay receipt

PR #14 — `Phase 4B verification: real-client Stone Warden E2E gate`

- merged at `781a7252c57829d5e2da7daeee8bf0ea6cef7f4e`;
- exact verification head `adcc25866bd498506b7bf6afbf77111454fad5fc`;
- CI `41028891703` — **SUCCESS**;
- Mineflayer 4.39.0 client using Minecraft 26.1 joined through pinned ViaProxy 3.4.14 into Paper 26.3;
- ViaProxy asset SHA-256 was verified before execution;
- client started the encounter through `/boss start` as a normal player;
- client observed the spawned Stone Warden entity;
- client stayed connected through the mechanic window;
- Seismic Slam and Stone Charge telemetry were both observed;
- authorized client-originated melee attacks completed the encounter;
- client emitted `STONE_WARDEN_E2E_CLIENT_PASS`;
- latest durable Stone Warden run finished as `COMPLETED`;
- telemetry assertions passed for `started`, `slam_telegraph`, `charge_telegraph`, and `completed`;
- E2E Paper/PostgreSQL volumes and ViaProxy process were cleaned after verification.

## Security boundary

The automated client verification does not weaken the normal server authentication path.

- normal Velocity configuration remains `online-mode=true`;
- the offline Paper stack exists only in `compose.e2e.yaml` for ephemeral CI;
- Paper movement validation remains enabled;
- the Mineflayer `client_tick_end` compatibility shim exists only in test client code;
- production boss/runtime code contains no E2E bypass.

## Invariants frozen

1. A boss entity is subordinate to one durable encounter run; PostgreSQL remains the durable lifecycle authority.
2. Combat Bukkit/entity state is in-memory only and is never treated as resumable after restart.
3. A run is persisted before boss spawn; spawn failure terminalizes the durable run.
4. Terminalization remains idempotent and conflicting terminal outcomes cannot both commit.
5. Only bound, arena-valid participants can damage Stone Warden.
6. Obvious horizontal/vertical arena cheese is bounded by server-owned checks.
7. Phase 4B grants no economic or item reward; combat correctness is isolated from reward anti-duplication semantics.
8. Real-network-client completion is a required regression gate, not merely an RCON/plugin-load smoke test.

## Explicit non-goals retained

Phase 4B does not freeze or implement:
- deterministic boss rewards;
- reward eligibility/history;
- item/currency reward recovery;
- a dungeon layout or dungeon progression;
- matchmaking/party expansion;
- broad boss-content authoring.

Those remain follow-up work.

## Next gate

Proceed to **Phase 4C — Boss Reward Delivery**.

The next change-set must derive reward eligibility from a completed durable encounter run and add retry-safe, auditable, anti-duplication reward delivery without changing the frozen Stone Warden combat semantics.