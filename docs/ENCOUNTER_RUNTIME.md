# Encounter runtime — Phase 4A

Status: **complete / frozen**

Implementation PR #10 merged at `f2f2d8704bb0cbfb542b0b04f64827e69f0b224c`.

Final-head CI `41020474477` and merge-state CI `41020981130` both succeeded. The formal freeze receipt is `docs/PHASE4A_FREEZE.md`.

Phase 4A establishes the smallest durable runtime needed before boss or dungeon content is allowed to exist. It proves lifecycle and recovery semantics first; it does not spawn mobs, award loot or introduce a player-facing encounter UI.

## Design boundary

Encounter state is split into two categories:

- **static definition content** lives in version-controlled resources and is validated at plugin startup;
- **durable run history** lives in PostgreSQL because attempts, participants and terminal outcomes must survive restart and remain queryable/auditable.

PostgreSQL stores the exact definition ID, version and SHA-256 fingerprint used by every run, so historical telemetry remains attributable even after a definition changes later.

## Definition registry

Definitions are listed by `encounters/index.txt` and stored as Java `.properties` resources. Phase 4A intentionally uses JDK-native parsing instead of adding a configuration dependency.

Current schema:

```properties
id=framework_probe
version=1
min-players=1
max-players=4
timeout-seconds=300
```

Validation fails closed for:

- unknown or missing keys;
- duplicate encounter IDs;
- IDs outside the server-safe canonical format;
- non-positive definition versions;
- invalid party bounds;
- timeouts outside the supported range.

Encounter IDs normalize to lowercase. A canonical SHA-256 fingerprint is computed from normalized semantic fields rather than raw file bytes, avoiding irrelevant hash changes from whitespace or property ordering.

`framework_probe` is synthetic framework content only. It does not spawn an entity or grant any reward.

## V6 persistence

`encounter_run` stores:

- stable `run_id`;
- encounter ID + definition version + definition SHA-256;
- lifecycle status;
- globally unique start correlation ID;
- start/end timestamps;
- terminal correlation ID and terminal reason;
- monotonic row version.

`encounter_participant` stores internal `player_id` membership for each run.

Lifecycle:

```text
ACTIVE -> COMPLETED
       -> FAILED
       -> ABANDONED
```

Terminal states cannot transition to another terminal outcome through the repository.

Merged V6 database checks enforce:

- canonical encounter IDs;
- positive definition versions;
- lowercase SHA-256 format;
- valid lifecycle status;
- ACTIVE rows have no terminal timestamp/correlation;
- terminal rows have terminal timestamp/correlation;
- non-negative row version.

The Java repository additionally requires a non-blank terminal reason for `FAILED` and `ABANDONED`; `COMPLETED` uses no reason. V6 does **not** duplicate that reason rule as a database CHECK. V6 is frozen after merge, so any future database-level strengthening of this invariant must be introduced through **V7 or later**, never by editing V6.

## Start idempotency

Starting a run is keyed by `start_correlation_id`.

```text
begin
  insert ACTIVE run by start correlation
  insert canonical participant set
  append encounter.run_started audit
commit
```

If the correlation already exists, the repository locks and re-reads the durable run. Replay succeeds only when encounter ID, version, definition fingerprint and participant set all match. Reusing the same correlation for different semantics fails with `IDEMPOTENCY_CONFLICT`.

Participant comparison uses Java UUID canonical ordering rather than relying on PostgreSQL UUID sort order.

## Terminal transitions

Each terminal mutation locks the run row first.

```text
begin
  SELECT run FOR UPDATE
  ACTIVE -> requested terminal outcome
  append one terminal audit keyed by run ID
commit
```

Rules:

- same terminal outcome replay returns the durable row without another audit;
- a different terminal outcome after termination fails closed;
- concurrent conflicting outcomes serialize on the run row, so only one can win;
- the terminal audit key is unique per run, giving one durable terminal telemetry event.

Failure-injection integration tests pre-reserve audit idempotency keys. They prove that an audit conflict at the end of the transaction rolls back the preceding run/participant or terminal writes.

## Restart semantics

Phase 4A does not pretend live world/mob state can be reconstructed yet.

During core startup, after V6 migration and before gameplay wiring completes, all stale `ACTIVE` rows from a previous process are locked and transitioned to:

```text
ABANDONED / SERVER_RESTART
```

Recovery is transactional and audited. Running it again finds no unresolved rows and creates no duplicate terminal events.

Future content may introduce resumable encounter types, but that must be an explicit later contract rather than silently changing Phase 4A semantics.

## Flyway/Paper classloader hardening

While adding startup recovery, full-stack CI exposed a latent runtime boundary: Flyway's ambient thread context classloader could fail to discover SQL migrations inside the plugin JAR even though the resources were packaged correctly.

The frozen persistence layer now:

- passes `DatabaseManager.class.getClassLoader()` explicitly to Flyway;
- requires the V1 migration sentinel to be visible through that classloader;
- enables missing-location failure;
- has CI inspect the shaded JAR directly for V1, V6 and encounter definition resources before Docker boot.

This keeps migration discovery tied to the plugin runtime instead of ambient server thread context. Both final-head and merge-state Paper smoke succeeded with this path.

## Diagnostics and telemetry

Audit event types:

- `encounter.run_started`
- `encounter.run_completed`
- `encounter.run_failed`
- `encounter.run_abandoned`

`/mcserver diagnostics` includes:

- total encounter run count;
- current ACTIVE encounter run count.

The durable tables retain participant and timing data for later PvE completion-rate and failure telemetry without introducing a separate analytics store in the MVP.

## Threading

Phase 4A contains no Bukkit entity/world mutation. Repository work is ordinary persistence work and is not exposed as a player command in this slice.

Future boss runtime code must keep Bukkit entity/world operations on the owning server thread and dispatch PostgreSQL lifecycle/telemetry work away from the tick thread through the existing persistence boundary.

## Tests

Unit validation covers:

- production registry load;
- canonical fingerprint stability;
- invalid ID/version/party/timeout;
- unknown properties;
- duplicate IDs.

PostgreSQL 18 integration coverage includes:

- start + participant persistence;
- exact start replay;
- semantic idempotency conflict;
- terminal replay;
- conflicting terminal outcome;
- concurrent COMPLETE vs FAIL with exactly one winner;
- restart abandonment exactly once;
- start-audit rollback injection;
- terminal-audit rollback injection.

Full-stack CI additionally checks:

- shaded JAR contains V1/V6 migrations and encounter resources;
- Paper loads migrations through the explicit plugin classloader;
- `encounter_run` exists after boot;
- encounter definitions validate during plugin enable;
- existing land/skill/market and Velocity/Geyser/Floodgate smoke remains green.

## Non-goals

Phase 4A does not include:

- boss AI/mechanics;
- mob spawning;
- arena/dungeon content;
- reward tables or loot delivery;
- economy payout;
- matchmaking/party-system expansion;
- player-facing encounter UI.

## Next slice

Phase 4B is **First Boss Encounter Mechanics**: one actual boss consuming this frozen runtime, with explicit start eligibility, spawn ownership, readable telegraphs, a small mechanic set, leash/anti-cheese boundaries and deterministic completion/failure wiring.

Durable loot/economy reward delivery remains out of 4B and should be handled as a separate Phase 4C transaction/recovery slice after boss lifecycle correctness is proven.
