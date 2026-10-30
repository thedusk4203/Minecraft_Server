# Phase 4A freeze — encounter runtime foundation

Status: **FROZEN / COMPLETE**

Frozen implementation base: `f2f2d8704bb0cbfb542b0b04f64827e69f0b224c`

Implementation PR: **#10**

Final-head CI: **41020474477 — SUCCESS**

## Exit gate

Phase 4A is frozen because the server now has a durable, restart-explicit encounter runtime that can support later boss and dungeon content without inventing persistence semantics inside the content slice.

The proven runtime can:

1. load and validate data-driven encounter definitions at startup;
2. bind a run to a stable encounter ID, version and SHA-256 definition fingerprint;
3. persist a canonical participant set against internal `player_identity` rows;
4. start a run idempotently by `start_correlation_id`;
5. move `ACTIVE` exactly once to one terminal outcome: `COMPLETED`, `FAILED` or `ABANDONED`;
6. reject conflicting terminal outcomes under retry/concurrency;
7. atomically commit lifecycle state and its audit event;
8. mark unresolved `ACTIVE` runs `ABANDONED / SERVER_RESTART` during startup recovery;
9. expose total/active encounter-run counts through server diagnostics;
10. boot successfully in the full Paper + PostgreSQL + Velocity/Geyser/Floodgate stack with Flyway loading migrations from the plugin classloader.

## Implementation receipt

- PR #10 — **Phase 4A: durable encounter runtime foundation**
- merge SHA: `f2f2d8704bb0cbfb542b0b04f64827e69f0b224c`
- final head: `eeb21e40b95898ac44baf19cb4f3512c25571c91`
- final-head CI: `41020474477` — SUCCESS
- PostgreSQL migration: `V6__encounter_runtime.sql`
- durable tables: `encounter_run`, `encounter_participant`
- synthetic framework definition: `framework_probe`

## Frozen correctness boundaries

- PostgreSQL stores durable run/participant history; static encounter content remains resource-driven.
- A run records the exact encounter definition version and SHA-256 fingerprint it was started with.
- `start_correlation_id` is globally unique; an exact replay returns the original run while semantic reuse fails closed.
- Participant IDs reference internal `player_identity`; duplicate participants are rejected before persistence.
- Terminal transitions row-lock the run so conflicting outcomes cannot both commit.
- A repeated request for the already-committed terminal outcome is idempotent; a different terminal outcome fails closed.
- Start + participant rows + start audit commit in one PostgreSQL transaction.
- Terminal state + terminal audit commit in one PostgreSQL transaction.
- Injected audit-write failure rolls back the associated lifecycle mutation.
- Restart recovery does not pretend Bukkit entity/world state survived. Any stale `ACTIVE` run is explicitly terminalized as `ABANDONED` with reason `SERVER_RESTART` before gameplay initialization completes.
- Flyway migration discovery is anchored to `DatabaseManager.class.getClassLoader()` and the runtime fails closed if the core migration sentinel is missing.
- CI verifies the shaded runtime JAR contains both migration resources and encounter-definition resources before the full stack starts.

## Known deliberate limitations

These are not Phase 4A blockers:

- `framework_probe` is synthetic and does not spawn mobs;
- there is no boss AI, encounter arena or combat scheduler yet;
- no loot, currency or item reward is granted by the encounter runtime;
- no matchmaking or party-management system is introduced;
- unresolved runs are abandoned on restart rather than reconstructing live entity/world state;
- diagnostics expose aggregate run counts, not a staff GUI or complete encounter-inspection console.

## Next gate — Phase 4B

Start **First Boss Combat Vertical Slice** as a separate implementation change-set consuming the frozen 4A runtime.

Scope should be limited to:

- one data-driven boss definition;
- server-owned spawn/despawn and encounter-zone lifecycle;
- participant eligibility bound to one durable encounter run;
- clear player-readable telegraphs;
- at least two mechanics beyond basic melee;
- explicit anti-cheese boundaries;
- death, disconnect, timeout and wipe/failure behavior;
- clean reset/despawn behavior;
- telemetry for attempts, participant deaths, duration and terminal outcome;
- deterministic Paper/integration tests for boss state-machine boundaries where practical.

Do **not** add durable loot/economy reward delivery in 4B. Reward eligibility and anti-duplication delivery should be implemented as Phase 4C after the combat lifecycle is proven.