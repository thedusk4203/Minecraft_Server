# Phase 4C2 Freeze — Crash-safe Stone Warden Item Reward Delivery

Status: **FROZEN**

Frozen runtime base merge: `8fc6cadee07fae4a9c5408c39e73ed97804add70`

Frozen tree: `54a5139eee4c8870fe41e584c6b16e4ddfd899ce`

## Scope frozen

Phase 4C2 completes the Stone Warden reward-delivery vertical slice on top of the frozen Phase 4A encounter runtime, Phase 4B combat contract and Phase 4C1 durable currency-reward contract.

Frozen behavior:
- PostgreSQL V8 `encounter_reward_item` durable item-delivery state, one-to-one with the existing Phase 4C1 reward identity;
- one concrete deterministic item definition: `stone_warden_shard` version 1 = one `AMETHYST_SHARD` named `Stone Warden Shard`;
- delivery token constrained to equal durable `reward_id`;
- item intent is eligible only after the corresponding Phase 4C1 reward is durably `CURRENCY_GRANTED` for a completed Stone Warden run;
- item lifecycle `PREPARED -> DELIVERY_PENDING -> DELIVERED`;
- exact marked Bukkit `ItemStack` snapshot plus SHA-256 is durable before inventory delivery;
- stable PDC delivery marker lets recovery distinguish an already persisted pending item from a second mint;
- marked/protected playerdata is saved before terminal PostgreSQL acknowledgement;
- terminal `DELIVERED` transition and audit event commit atomically in PostgreSQL;
- stale markers after terminal acknowledgement are cleanup-only and can never authorize another item;
- join, respawn, periodic online reconciliation and `/boss recover` converge interrupted deliveries;
- full inventory leaves the reward recoverably pending rather than discarding it;
- duplicate, foreign, orphan, semantically drifted or integrity-invalid markers/payloads fail closed;
- pending marked reward items are protected from movement/use/drop/death-loss until delivery is acknowledged;
- diagnostics and `BOSS_REWARD_TELEMETRY` expose item preparation, pending delivery, completion, cleanup and conflict paths;
- solo and two-client real-network-client E2E verify visible item receipt plus durable terminal state;
- the frozen Phase 4C1 250-COINS grant contract is unchanged.

## Verification receipts

### Phase 4C2 implementation

PR #20 — `Phase 4C2: crash-safe Stone Warden item reward delivery`

- implementation head `6490cbdf939f24f461bb3bf3e35e90f780f315fc`;
- final-head CI `41060685800` attempt 2 — **SUCCESS**;
- merged into `foundation/v0.1` at `a12e52e63eb766fb619d955ca8cae1d53635a667`;
- V8 is packaged in the shaded runtime JAR and creates `encounter_reward_item` during Paper boot;
- PostgreSQL integration coverage verifies replay, concurrency and terminal-audit rollback paths;
- solo real-client E2E observed one `Stone Warden Shard` and exactly one durable item row reached `DELIVERED`;
- two-client party E2E observed the shard for both clients and exactly two durable item rows reached `DELIVERED`;
- the existing 250-COINS solo and party assertions remained green.

The first CI attempt on the same implementation head passed build and PostgreSQL integration but failed while acquiring an external Velocity crossplay plugin because of a TLS/PKIX certificate-chain failure. No Phase 4C2 runtime change was needed to make the exact implementation head pass on rerun.

### Deterministic crossplay acquisition hardening

PR #21 — `CI: pin and verify Velocity crossplay artifacts`

- final head `22a29bb69402dc06d1ca22ab745c744b16fe1e65`;
- exact PR merge-ref `e0d45b9d0719fe558493b2c128d9dbc65dde9070`;
- CI `41086582597` — **SUCCESS**;
- merged into `foundation/v0.1` at `8fc6cadee07fae4a9c5408c39e73ed97804add70`;
- Geyser Velocity is fetched from the official Modrinth distribution with exact version/build, SHA-512 and Velocity descriptor validation;
- Floodgate Velocity build 141 is reproduced from exact official source commit `81b65ccb766bf8024858ebf3eefa6974056aecc5` with recursive submodules, upstream build recipe and exact descriptor validation;
- TLS verification remains enabled; the failing distribution endpoint is not trusted or bypassed;
- crossplay artifacts are prepared after `gradle clean build`, so Gradle clean cannot delete the plugin JARs before Compose starts;
- smoke checks require both host artifacts to be regular non-empty files and require both Geyser/Floodgate runtime configs to be created by Velocity;
- this hardening changed CI/local-development artifact acquisition only; Phase 4C2 gameplay, schema, reward semantics and production plugin logic were unchanged.

### Frozen merge-state content receipt

The repository workflow automatically runs `push` only on `main`, while this phase is integrated on `foundation/v0.1`; therefore GitHub did not create a distinct post-merge push run for `8fc6cadee07fae4a9c5408c39e73ed97804add70`.

The final PR #21 CI is nevertheless a byte-content-equivalent merge-state verification:
- CI `41086582597` checked out merge-ref `e0d45b9d0719fe558493b2c128d9dbc65dde9070`;
- that merge-ref has parents `a12e52e63eb766fb619d955ca8cae1d53635a667` and `22a29bb69402dc06d1ca22ab745c744b16fe1e65`;
- the actual frozen merge `8fc6cadee07fae4a9c5408c39e73ed97804add70` has the same two parents;
- both commits resolve to the exact same tree `54a5139eee4c8870fe41e584c6b16e4ddfd899ce`.

CI `41086582597` passed:
- Gradle build and unit tests;
- shaded V1/V6/V7/V8 runtime-resource verification;
- PostgreSQL integration tests;
- deterministic Geyser/Floodgate acquisition and descriptor verification;
- Paper/Flyway full-stack boot through V8;
- gameplay dependency and market regression checks;
- Java/Bedrock Velocity crossplay smoke with both Geyser and Floodgate loaded/configured;
- solo Stone Warden real-client combat, exactly one durable 250-COINS reward and exactly one durable `DELIVERED` shard item;
- two-client Stone Warden party combat, exactly two durable participants, two currency rewards and two durable `DELIVERED` shard items;
- E2E cleanup.

## Invariants frozen

1. Item eligibility derives from the existing durable completed-run participant/reward authority; transient Bukkit entity state cannot create eligibility.
2. The frozen Phase 4C1 250-COINS semantics, reward identity and economy idempotency contract remain unchanged.
3. One durable Phase 4C1 reward ID owns at most one V8 item-delivery identity, and its delivery token equals that reward ID.
4. Item key, version, material and amount are immutable for an existing durable delivery identity; semantic drift fails closed.
5. The exact marked item snapshot and SHA-256 become durable before inventory delivery is attempted.
6. A terminal `DELIVERED` acknowledgement is not allowed on the intended path until marked playerdata has been saved successfully.
7. Recovery in `DELIVERY_PENDING` reuses exactly one matching persisted marker; zero markers restores the durable snapshot, while multiple/foreign/orphan markers fail closed.
8. A full inventory remains a recoverable pending condition and cannot silently lose or terminally acknowledge the reward.
9. The PostgreSQL terminal state transition and terminal audit event are atomic; injected terminal-audit failure rolls the transition back.
10. Once PostgreSQL is `DELIVERED`, any residual marker is cleanup-only and cannot authorize another grant.
11. Join, respawn, periodic-online and manual recovery surfaces converge on the same durable state machine.
12. Real-network-client solo and party item completion remain regression gates for subsequent PvE work.

## Explicit non-goals retained

Phase 4C2 does not freeze or implement:
- random loot tables or a generic loot catalog;
- multiple boss reward definitions;
- dungeon content or encounter sequencing;
- broad item-progression balance;
- monetized or paid reward mechanics;
- a distributed transaction manager spanning Bukkit playerdata and PostgreSQL.

## Next gate

Proceed to the **first MVP dungeon or equivalent structured PvE activity** required by Phase 4.

Before implementation, define one concrete reviewable activity contract that consumes the frozen Phase 4A–4C2 foundations and specifies its entry rule, party-size assumptions, 2–3 encounter beats, boss role, reset/failure behavior, disconnect/reconnect behavior, deterministic reward integration and real-client acceptance gate. Do not reopen the frozen Stone Warden reward semantics merely to author the new content.
