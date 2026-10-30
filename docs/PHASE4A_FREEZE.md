# Phase 4A freeze — Encounter Runtime Foundation

Status: **FROZEN / COMPLETE**

Implementation PR: **#10**

Implementation merge SHA: `f2f2d8704bb0cbfb542b0b04f64827e69f0b224c`

Final implementation head: `eeb21e40b95898ac44baf19cb4f3512c25571c91`

Final-head CI: **41020474477 — SUCCESS**

Merge-state CI: **41020981130 — SUCCESS**

## Exit gate

Phase 4A is frozen because the server now has a durable encounter runtime whose lifecycle is proven independently of boss mechanics or reward delivery.

The frozen foundation proves:

1. encounter definitions are data-driven and fail startup validation closed;
2. every run records a stable run ID plus the exact definition ID/version/fingerprint used;
3. participant membership is durable and tied to internal player identities;
4. run start is idempotent by correlation ID and semantic replay is verified;
5. terminal outcomes serialize on the run row, so conflicting completion/failure decisions cannot both commit;
6. start/terminal audit writes share the transaction with the state they describe;
7. unresolved ACTIVE runs from a previous server process receive an explicit `ABANDONED / SERVER_RESTART` outcome;
8. encounter run counts are visible through administrative diagnostics;
9. migration and encounter resources are verified inside the shaded runtime JAR;
10. Paper, PostgreSQL, existing Phase 1–3 gameplay and Java/Bedrock crossplay still boot together.

## Frozen persistence contract

V6 adds:

- `encounter_run`
- `encounter_participant`

Frozen run lifecycle:

```text
ACTIVE -> COMPLETED
       -> FAILED
       -> ABANDONED
```

The durable row stores:

- `run_id`
- encounter ID
- encounter definition version
- canonical semantic SHA-256 fingerprint
- lifecycle status
- start correlation ID
- start/end timestamps
- terminal correlation ID
- terminal reason where applicable
- row version

Participants reference `player_identity.player_id`.

V6 is now immutable. Any future database invariant change must use **V7 or later** rather than editing the merged migration.

## Frozen definition contract

Definitions are loaded from `encounters/index.txt` and JDK `.properties` resources.

Phase 4A supports these semantic fields:

- `id`
- `version`
- `min-players`
- `max-players`
- `timeout-seconds`

Unknown/missing keys, duplicate IDs, invalid IDs, invalid versions, invalid party bounds and invalid timeouts fail closed.

The fingerprint is generated from canonical semantic values rather than raw file bytes, so irrelevant whitespace/property-order changes do not alter historical definition identity.

`framework_probe` remains synthetic test content only.

## Idempotency and concurrency contract

### Start

`start_correlation_id` is globally unique.

Exact replay must match:

- encounter ID;
- encounter version;
- definition SHA-256;
- canonical participant set.

A reused correlation with different semantics fails with an idempotency conflict.

### Terminal outcome

Terminal mutations lock the run row before transition.

- replaying the already durable terminal outcome is idempotent;
- attempting a different terminal outcome fails closed;
- concurrent COMPLETE/FAIL attempts serialize so one outcome wins;
- exactly one terminal audit identity is allowed per run.

## Restart contract

Phase 4A intentionally does **not** claim that live world/entity state is resumable.

At core startup, any stale `ACTIVE` run from a previous process is transitioned transactionally to:

```text
ABANDONED
reason = SERVER_RESTART
```

The transition is audited. Re-running recovery after success finds no unresolved row and creates no second terminal event.

A future resumable encounter type would require a new explicit contract; it must not silently reinterpret this freeze.

## Failure-injection evidence

PostgreSQL integration tests pre-reserve audit idempotency keys at the transaction tail.

Proven rollback boundaries:

- if `encounter.run_started` audit insertion conflicts, the new run and participant rows roll back;
- if a terminal audit insertion conflicts, the terminal state change rolls back and the run remains ACTIVE.

This proves telemetry cannot claim an outcome that failed to commit, and committed encounter state cannot silently lose its required audit tail through these paths.

## Flyway/Paper runtime hardening

Full-stack CI exposed a latent classloader boundary while 4A was being built: Flyway's ambient thread context classloader could fail to discover plugin migrations under Paper even though they were packaged in the JAR.

The frozen persistence startup now:

- explicitly supplies `DatabaseManager.class.getClassLoader()` to Flyway;
- verifies the V1 migration sentinel is visible through that plugin classloader;
- fails on missing migration locations;
- verifies the shaded JAR contains V1, V6 and encounter resources before Docker/Paper smoke begins.

Final-head and merge-state Paper smoke both prove the corrected runtime path.

## Diagnostics / telemetry

Audit event families:

- `encounter.run_started`
- `encounter.run_completed`
- `encounter.run_failed`
- `encounter.run_abandoned`

`/mcserver diagnostics` exposes:

- total encounter runs;
- active encounter runs.

This is enough observability for the first real boss slice without adding a separate analytics system.

## Deliberate non-goals

4A does not include:

- boss entities or AI;
- arena content;
- player-facing encounter start UX;
- damage/mechanic scripting;
- loot/reward tables;
- economy reward settlement;
- matchmaking or a new party subsystem.

## Next gate — Phase 4B

Start **Phase 4B — First Boss Encounter Mechanics** on a fresh branch from the frozen foundation.

4B should prove one actual boss encounter consuming the frozen runtime:

- one real boss definition;
- explicit start eligibility and participant capture;
- controlled spawn/despawn ownership;
- readable telegraphs and a small mechanic set;
- arena/leash/anti-cheese boundaries;
- timeout/disconnect/death failure handling;
- durable run completion/failure wiring;
- Java/Bedrock-compatible server-side presentation;
- deterministic runtime tests where practical and full Paper smoke.

**Do not add durable loot/economy reward delivery in 4B.** Reward tables and exactly-once reward settlement should be a separate Phase 4C slice after boss lifecycle correctness is proven.
