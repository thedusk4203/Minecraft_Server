# Phase 4D Design — Stone Vault Expedition

Status: **design candidate / implementation gate**

## Objective

Deliver the first short, replayable structured PvE activity without creating a second major game mode or reopening the frozen Phase 4A–4C2 contracts.

The concrete MVP activity is **Stone Vault Expedition**: a controlled same-world combat activity with two short pre-boss beats followed by the existing Stone Warden encounter.

This design deliberately chooses a controlled same-world implementation for v0.1 rather than a dedicated dungeon server or dynamically-instanced world. The reason is operational: the MVP must validate structured PvE sequencing, recovery and party behavior before adding world-instancing infrastructure.

## Product fit

Stone Vault remains inside the survival/RPG product loop:

```text
survival progression
  -> form a small party
  -> enter Stone Vault
  -> clear two readable combat beats
  -> defeat Stone Warden
  -> receive the existing deterministic Stone Warden rewards
  -> return to survival/economy progression
```

It does not add a lobby queue, separate shard, separate character progression, separate currency or separate game mode.

## Frozen dependencies

Phase 4D consumes the following contracts as frozen dependencies:

- Phase 4A durable `encounter_run` and canonical participant authority;
- Phase 4B Stone Warden combat mechanics and real-client behavior;
- Phase 4C1 exactly-once 250 COINS reward semantics;
- Phase 4C2 crash-safe `Stone Warden Shard` item-delivery semantics;
- existing Java + Bedrock crossplay smoke and market regression gates.

The Stone Vault implementation must not weaken or reinterpret those contracts.

## Activity identity

- activity id: `stone_vault`
- activity version: `1`
- boss encounter id: `stone_warden`
- party size: `1-4`
- target duration: approximately `4-8 minutes`
- controlled arena radius: implementation constant/data field, initially small enough for one local survival area
- completion authority: the same durable Stone Warden `encounter_run` that ultimately reaches `COMPLETED`

The activity definition must have a deterministic semantic fingerprint. Changing party assumptions, beat order, required counts or other semantic fields requires a version bump rather than silently mutating version 1.

## Entry contract

Player-facing MVP entry command:

```text
/dungeon start [player...]
```

Rules:

1. The command sender is the party leader and is always included.
2. Additional names must resolve to online players.
3. Party members must be alive and in the same world.
4. Party size must be 1–4.
5. A participant cannot already be pending/active in Stone Vault or Stone Warden.
6. The durable encounter run and Stone Vault progress row must be persisted before any activity mobs are spawned.
7. If persistence fails, the activity does not mutate the world.
8. If a participant becomes unavailable after durable start but before world activation, the run fails/abandons with a diagnostic terminal reason; it must not be silently reused as a future attempt.

`/boss start` remains the standalone frozen Stone Warden developer/admin surface and must retain its existing semantics.

## Durable authority model

Stone Vault does **not** introduce a second independent run lifecycle.

One Stone Vault attempt owns one existing Phase 4A `encounter_run` using the frozen `stone_warden` encounter definition and canonical participant set. A new Phase 4D progress table may attach structured-activity metadata one-to-one to that run, but:

- `encounter_run.status` remains the terminal authority;
- the canonical participant set remains `encounter_participant`;
- activity progress cannot change participants after durable start;
- the final boss uses the same run instead of creating a child/parallel Stone Warden run;
- Phase 4C reward services see the same completed Stone Warden run they already understand.

This is the central compatibility constraint for Phase 4D.

## Beat contract

### Beat 1 — Outer Seal

Purpose: teach the party that the activity has gated progression before the boss.

MVP behavior:

- spawn a deterministic small pack of stone-themed melee sentinels around the arena;
- initial target: `4` sentinels;
- only mobs tagged to the current durable run count toward progress;
- unrelated world mobs cannot advance the beat;
- when every tagged Beat-1 sentinel is dead, persist the transition to Beat 2 before spawning Beat-2 mobs.

Player-facing messaging must clearly announce entry and beat completion.

### Beat 2 — Inner Guard

Purpose: add a second readable combat beat with mixed pressure while remaining cheap to test.

MVP behavior:

- spawn a deterministic mixed guard pack;
- initial target composition: `2` melee guards + `2` ranged guards;
- only correctly tagged current-run mobs count;
- when all Beat-2 guards are dead, persist the transition to Beat 3 before activating the boss;
- after durable transition, activate Stone Warden using the already-created run.

### Beat 3 — Stone Warden

Purpose: reuse the frozen boss vertical slice as the dungeon climax.

Requirements:

- use the existing Stone Warden combat implementation rather than copying its mechanics;
- do not create a second encounter run;
- preserve current boss health scaling, telegraphs, leash, timeout, enrage and damage validation;
- preserve the frozen 250 COINS and `Stone Warden Shard` completion rewards unchanged;
- Stone Warden completion terminally completes the existing run exactly once.

## Structured progress persistence

Phase 4D may add one migration with a one-to-one table keyed by the frozen encounter run identity, conceptually:

```text
structured_activity_run
- run_id PK/FK -> encounter_run
- activity_id
- activity_version
- definition_sha256
- current_beat
- beat_progress
- version
- created_at
- updated_at
```

Required invariants:

- one structured activity row per encounter run;
- only `stone_vault` version 1 is accepted by the first implementation;
- immutable activity identity/fingerprint after insertion;
- transitions are monotonic;
- repeated transition calls are idempotent if semantics match;
- stale/out-of-order transitions fail closed;
- no terminal activity state is allowed to override `encounter_run.status`;
- DB transition and corresponding audit event should be atomic where practical.

Beat mob entities are ephemeral Bukkit state. Durable state records which beat the run had reached; it does not pretend spawned mob UUIDs survive restart.

## Disconnect behavior

Disconnect does not remove a player from the canonical participant set.

During Beats 1–3:

- an offline participant remains part of the durable run;
- remaining online participants may continue;
- if no eligible participant remains for the existing boss-manager grace period, the run fails/abandons through the existing encounter authority;
- reconnecting before terminal failure restores normal participation if the run is still active;
- reconnect cannot create a new reward identity or new activity run.

No backfilling/replacing party members after durable start.

## Death / wipe behavior

The MVP uses the same practical failure philosophy as the frozen boss slice:

- individual death does not rewrite the canonical participant set;
- living/eligible members may continue;
- if the activity reaches a state with no active participants for the configured grace period, the attempt terminates as failure;
- failed/abandoned runs cannot produce Phase 4C rewards.

The MVP does not add revive tokens, checkpoint respawns or paid retries.

## Server restart behavior

The frozen Phase 4A startup recovery currently abandons active encounter runs after restart.

Stone Vault version 1 intentionally consumes that rule:

- any active Stone Vault/Stone Warden attempt present during server startup becomes `ABANDONED` with the existing restart recovery path;
- no activity wave or boss is reconstructed after restart in v1;
- abandoned attempts are never reward-eligible;
- a later new `/dungeon start` creates a new durable run/correlation identity.

This yields an unambiguous and testable restart contract instead of partial world reconstruction.

## Arena / anti-cheese boundaries

Stone Vault v1 uses a controlled local arena centered on the leader's entry location.

Requirements:

- all activity mobs carry run-scoped PDC markers;
- tagged mobs are removed during terminal cleanup;
- activity mobs are leashed to the arena and cannot become persistent survival-world mobs;
- only canonical participants can damage/count against activity mobs where practical;
- unrelated players/world entities cannot advance beats;
- participants outside the activity radius are not considered eligible combatants until returning;
- world blocks are not programmatically destroyed by the activity.

A handcrafted map/portal can replace the temporary controlled arena in a later content pass without changing the durable activity contract.

## Rewards

Phase 4D v1 intentionally adds **no new currency or item reward definition**.

Successful Stone Vault completion produces exactly the already-frozen Stone Warden rewards for each canonical eligible participant:

- `250 COINS` via Phase 4C1;
- one `Stone Warden Shard` via Phase 4C2.

This isolates the structured-activity risk from economy expansion. New dungeon-specific loot is a later reviewable change and must not be smuggled into this gate.

## Telemetry

Structured logs must make the activity reconstructable operationally.

Minimum events:

- `ACTIVITY_TELEMETRY event=started`
- `ACTIVITY_TELEMETRY event=beat_started beat=outer_seal`
- `ACTIVITY_TELEMETRY event=beat_completed beat=outer_seal`
- `ACTIVITY_TELEMETRY event=beat_started beat=inner_guard`
- `ACTIVITY_TELEMETRY event=beat_completed beat=inner_guard`
- `ACTIVITY_TELEMETRY event=boss_activated boss=stone_warden`
- `ACTIVITY_TELEMETRY event=completed`
- `ACTIVITY_TELEMETRY event=failed reason=...`
- `ACTIVITY_TELEMETRY event=abandoned reason=...`

Events should include durable run id and participant count where relevant.

## Command / diagnostics surface

MVP command:

```text
/dungeon start [player...]
/dungeon status
```

Admin diagnostics should be able to expose at least:

- active Stone Vault count;
- durable run id;
- current beat;
- participant count;
- whether Stone Warden has been activated.

A manual reward recovery command remains `/boss recover`; Phase 4D does not create another item-delivery recovery mechanism.

## Test plan

### Unit tests

- activity definition validation and fingerprint determinism;
- invalid party/beat/config values rejected;
- monotonic beat transition validation;
- activity mob marker semantics where logic can be isolated.

### PostgreSQL integration tests

- create progress row only for a valid existing run;
- duplicate semantic start is idempotent;
- duplicate conflicting semantics fail closed;
- Beat 1 -> Beat 2 -> Beat 3 transitions are monotonic/idempotent;
- out-of-order transitions fail;
- progress fingerprint cannot drift;
- terminal encounter runs cannot be advanced;
- rollback/audit failure does not leave half-applied progress where an atomic boundary is required.

### Runtime smoke

- V9 (if introduced) packaged in shaded runtime JAR;
- Paper/Flyway boots cleanly through the new migration;
- `/dungeon` wiring loads without affecting `/boss`;
- existing market, crossplay and boss smoke checks remain green.

### Real-client E2E

A two-client test is the Phase 4D acceptance path:

1. two real network clients join;
2. leader starts Stone Vault with the second client;
3. both clients clear Outer Seal through normal attacks;
4. both clear Inner Guard through normal attacks;
5. Stone Warden activates using the same durable run;
6. both defeat Stone Warden through normal attacks;
7. database verifies one completed canonical run with exactly two participants;
8. activity progress verifies Beat 3/boss activation reached the expected durable state;
9. exactly two frozen 250-COINS reward rows reach their existing terminal state;
10. exactly two frozen shard deliveries reach `DELIVERED`;
11. replay/recovery queries verify no duplicate rewards.

The existing standalone solo and party Stone Warden E2Es remain mandatory regression gates.

## Failure-path acceptance

Before Phase 4D can freeze, tests must cover at least:

- participant unavailable between durable start and world activation;
- tagged wave mob removed/missing unexpectedly;
- no active participants / wipe;
- boss activation failure after Beat 2 was durably advanced;
- repeated beat-completion callback;
- server restart with an active Stone Vault run;
- stale activity mob/entity event after terminal cleanup;
- reward reconciliation after successful completion.

The implementation must prefer explicit terminal failure with diagnostics over guessing/reconstructing ambiguous state.

## Non-goals

Phase 4D v1 does not include:

- a separate dungeon backend server;
- dynamically cloned worlds;
- matchmaking/queue infrastructure;
- custom dungeon currency;
- random loot tables;
- dungeon keys sold for real money;
- checkpoints/revive economy;
- dozens of rooms or procedural generation;
- new Stone Warden reward amounts/items;
- rewriting the frozen boss mechanics.

## Implementation slices

Implementation should remain reviewable even if delivered through more than one PR:

1. **4D1 — Durable structured-activity progress foundation**
   - data-driven Stone Vault definition/fingerprint;
   - V9 progress persistence;
   - transition repository + integration tests.

2. **4D2 — Runtime sequencing**
   - `/dungeon` command;
   - Outer Seal + Inner Guard tagged wave runtime;
   - bridge into the existing Stone Warden manager using the same durable run;
   - cleanup, failure, reconnect and telemetry behavior.

3. **4D3 — Real-client acceptance and freeze**
   - two-client full Stone Vault E2E;
   - durable activity/reward assertions;
   - failure/restart regression where practical;
   - freeze receipt only after one exact tested implementation head is fully green.

## Exit gate

Phase 4D is complete only when:

- Stone Vault v1 has a frozen definition and semantic fingerprint;
- party 1–4 entry is server-authoritative;
- two pre-boss beats progress only from current-run tagged mobs;
- Stone Warden is activated as the final beat without creating a second durable run;
- a two-client party can complete all beats through normal client actions;
- restart/disconnect/failure paths terminate unambiguously;
- completion produces no more and no less than the existing frozen Stone Warden rewards;
- replay/recovery cannot intentionally duplicate currency or item rewards;
- standalone Stone Warden, market and crossplay regression gates remain green.
