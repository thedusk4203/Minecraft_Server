# Phase 4C1 Freeze — Durable Stone Warden Currency Rewards

Status: **FROZEN**

Frozen base merge: `aa956583862fab7705db93eee917c79938c355f2`

## Scope frozen

Phase 4C1 establishes the first durable boss-reward slice on top of the frozen Phase 4A encounter runtime and Phase 4B Stone Warden combat contract.

Frozen behavior:
- PostgreSQL V7 `encounter_reward` durable reward state;
- one concrete reward definition, `stone_warden_completion` version 1;
- exactly 250 `COINS` for each canonical participant of an eligible Stone Warden run;
- durable reward identity unique on `(run_id, player_id, reward_key, reward_version)`;
- currency grants accepted only for a durable `COMPLETED` Stone Warden run;
- reward coverage validated against the canonical durable encounter participant set;
- reward semantics fail closed if encounter/reward identity is rebound to different meaning;
- economy ledger mutation, reward-state acknowledgement and reward audit commit in one PostgreSQL transaction;
- deterministic economy idempotency key derived from durable `reward_id`;
- committed replay returns replay semantics instead of creating a second credit;
- concurrent grant attempts serialize through durable encounter/reward row locking;
- startup plus periodic reconciliation repairs the encounter-completion -> currency-reward gap;
- periodic reconciliation is non-overlapping and database work stays off the Bukkit main thread;
- solo and two-client real-network-client E2E verify reward completion;
- no Bukkit inventory/item reward mutation exists in this slice.

## Verification receipts

### Phase 4C1 implementation

PR #17 — `Phase 4C1: durable Stone Warden currency rewards`

- implementation head `9d3d678b811759d932449ad0a273cacd0742f10e`;
- final-head CI `41039063048` — **SUCCESS**;
- merged into `foundation/v0.1` at `d34b3303f6322a84fc213a1b40de5f442956d7d8`;
- V7 migration is packaged in the shaded runtime JAR and creates `encounter_reward` during Paper boot;
- integration coverage proves ACTIVE-run rejection, completed-run grant, exact replay, reconciliation, concurrent grant serialization and injected audit-conflict rollback;
- solo real-client E2E produced exactly one `CURRENCY_GRANTED` reward linked to one `+250 COINS` `STONE_WARDEN_REWARD` economy transaction;
- two-client party E2E produced exactly two independently linked rewards for the two durable participants.

### Party E2E hardening

The first PR #17 merge-state run `41040057776` passed build/unit/PostgreSQL/full-stack checks and the complete solo reward gate, then failed before the party encounter started because the test clients spawned on uneven fresh-world terrain and the old harness always forced the lower client uphill.

PR #18 — `Test: harden Stone Warden party E2E convergence`

- exact head `980c3d8d88db4af3c3dd1a07fc05cb2a7d92a6eb`;
- CI `41051311119` — **SUCCESS**;
- merged at `aa956583862fab7705db93eee917c79938c355f2`;
- the harness now prefers moving the higher client toward the lower client, reverses direction as a terrain fallback and jumps after detected movement stalls;
- convergence remains normal client-authored movement through Mineflayer -> ViaProxy -> Paper;
- no RCON teleport or production gameplay bypass was introduced;
- production boss, encounter, economy and reward code was unchanged by this hardening PR.

### Frozen merge-state receipt

CI `41055264861` on exact frozen head `aa956583862fab7705db93eee917c79938c355f2` — **SUCCESS**.

The frozen merge state passed:
- Gradle build and unit tests;
- shaded runtime-resource verification;
- PostgreSQL integration tests;
- Paper/Flyway full-stack boot;
- reward/encounter runtime checks;
- existing gameplay dependency, market and Java/Bedrock proxy regression checks;
- solo Stone Warden real-client combat plus one durable currency reward;
- two-client Stone Warden party combat plus exactly two durable currency rewards;
- E2E cleanup.

## Invariants frozen

1. Reward authority comes from the durable encounter run and canonical durable participant rows, never transient Bukkit entity state.
2. Currency can be granted only after the corresponding Stone Warden run is durably `COMPLETED`.
3. One `(run, participant, reward key, reward version)` maps to one durable reward identity.
4. Reward key/version/currency/amount semantics are immutable for an existing reward identity; semantic drift fails closed.
5. The `+250 COINS` ledger transaction and transition from `PENDING` to `CURRENCY_GRANTED` are one PostgreSQL transaction together with the grant audit event.
6. The durable reward ID owns the economy idempotency key, so retries cannot intentionally mint another currency grant.
7. Concurrent grant/recovery calls serialize and converge on one committed reward per participant.
8. A failure at the final reward-audit boundary rolls back both the economy mutation and reward-state advancement.
9. Reconciliation may create missing reward intents for an already completed run, but FAILED, ABANDONED or still-ACTIVE runs remain ineligible.
10. Real-network-client solo and party reward completion remain regression gates for subsequent PvE work.
11. Phase 4C1 freezes currency delivery only; it does not claim Bukkit inventory and PostgreSQL are atomic.

## Explicit non-goals retained

Phase 4C1 does not freeze or implement:
- item reward delivery;
- Bukkit inventory recovery for boss rewards;
- a generic/random loot-table framework;
- dungeon content;
- broad boss-content authoring;
- monetized or paid reward mechanics.

## Next gate

Proceed to **Phase 4C2 — Crash-safe Stone Warden Item Reward Delivery**.

The next slice must preserve every frozen 4C1 currency invariant while adding one concrete deterministic item reward with a durable delivery identity, explicit playerdata-before-terminal-DB ordering, restart/reconnect/manual recovery, protected pending markers, failure-injection coverage, diagnostics and real-client verification.
