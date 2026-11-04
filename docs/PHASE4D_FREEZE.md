# Phase 4D Freeze — Stone Vault Expedition v1

Status: **FROZEN**

Frozen runtime merge on `main`: `49a6675bec70b5ac7fac9aae65680cc68bd9d473`

Frozen runtime tree: `80d694a3b2a3b377681d23a7fbab7dcb6021ed37`

Authoritative post-merge `main` CI: [#208 / run 41803211457](https://github.com/thedusk4203/Minecraft_Server/actions/runs/41803211457), attempt 1 — **SUCCESS** (47 successful steps, zero failures).

## Scope frozen

Phase 4D delivers **one** replayable, same-world, server-authoritative Stone Vault PvE activity on top of the already-frozen Phase 4A–4C2 contracts:

- data-defined `stone_vault` activity ID/version/fingerprint with 1–4 canonical party members;
- entry via normal player `/dungeon start [player...]`, with process-local admission reservations and canonical durable participant authority;
- PostgreSQL V9 `structured_activity_run`, exactly one activity progress record bound to the *existing* `stone_warden` encounter run, monotonic/replay-safe beat progression and transactional audit;
- **Beat 1 — Outer Seal:** four run-tagged melee Stone Sentinels;
- **Beat 2 — Inner Guard:** two run-tagged melee guards plus two ranged guards;
- **Beat 3 — Stone Warden:** the frozen boss runtime activates against the **same** durable run, with existing damage validation, mechanics, terminalization and reward services;
- wave spawns require supported feet/head room and continuous sampled footing, with deterministic cliff fallback and fail-closed behavior if no valid spawn layout exists;
- arena eligibility/leash, cleanup, disconnect, player loss, wipe, restart and unavailable pre-activation participant behavior remain fail-closed with durable terminal reasons;
- exactly **250 COINS** and one `stone_warden_shard` (`AMETHYST_SHARD`) per eligible canonical participant on successful completion, with the frozen Phase 4C1/4C2 idempotency keys and delivery semantics;
- scoped `ACTIVITY_TELEMETRY` for entry, beat progression, boss activation, completion/failure, plus diagnostics and CI artifacts.

There is **no** separate dungeon currency, new boss reward definition, new encounter authority, queue backend, dedicated dungeon world, paywall or new persistence authority.

## Implementation and corrective-change receipts

The Phase 4D implementation evolved through reviewable PRs, with independent immutable behavior preserved:

| PR | Scope |
| --- | --- |
| #23 | Stone Vault design and approval contract |
| #24 | V9 one-to-one structured activity persistence |
| #25 | Runtime sequencing, tagged waves, existing-run boss activation |
| #26 | Two-client Stone Vault full-loop positive E2E |
| #27–#28 | Real-client diagnostics and CI failure artifact hardening |
| #34 | Deterministic isolated Superflat E2E world (CI fixture only) |
| #33 | Production terrain-supported wave-spawn planner and unit tests |
| #35 | Real-client active-run Paper restart recovery acceptance |
| #31 | Real-client participant-loss and reconnect acceptance |
| #37 | Deterministic real-client loss between durable start and activity activation |
| #38 | Stone Vault reward reconciliation/replay PostgreSQL integration and exactly-one-shard-per-client recovery E2E |
| #36 | Isolated E2E fault injection for missing mobs, boss activation failure, duplicate/stale callbacks |

The final corrective implementation PR **#36** was tested on head `2a776d704d5e7288c0d519fa92cdac234e715164` in [CI #207 / 41801665801](https://github.com/thedusk4203/Minecraft_Server/actions/runs/41801665801) — **SUCCESS**. It merged at `49a6675bec70b5ac7fac9aae65680cc68bd9d473`.

The actual merged commit then passed [CI #208 / 41803211457](https://github.com/thedusk4203/Minecraft_Server/actions/runs/41803211457) — **SUCCESS**; therefore the acceptance claim does not rely on a speculative PR-head result or on a pre-merge-only run.

## Final runtime acceptance evidence

The **same merged-main CI #208** succeeded at:

1. Java 25 / Gradle 9.8.2 core build, unit tests, shaded V1–V9 resources and PostgreSQL Testcontainers integration;
2. Paper/Flyway, existing gameplay dependencies, market regression, Velocity/Geyser/Floodgate acquisition and proxy/translation smoke;
3. standalone Stone Warden real-client solo and party normal combat, server-confirmed damage, mechanics, canonical participant binding and frozen currency/shard delivery;
4. Stone Vault real-client two-player Outer Seal → Inner Guard → same-run Stone Warden → `COMPLETED`, one V9 attachment at Beat 3, two canonical participants, two exactly-once 250-COINS grants and two `DELIVERED` shard intents;
5. real-client participant loss and normal cooldown-respecting reconnect: `FAILED|NO_ACTIVE_PARTICIPANTS`, no reward and no terminal-run resurrection;
6. active Stone Vault Paper restart: `ABANDONED|SERVER_RESTART` with preserved canonical participants and no reward;
7. client disconnect **after** durable encounter start and before V9 world activation: `FAILED|START_PARTICIPANT_UNAVAILABLE`, attachment/participants/reward/item/terminal-audit counts `1|1|0|0|1`;
8. deliberately removed tagged wave mob: `FAILED|WAVE_ENTITY_MISSING` at Beat 1; no rewards and exactly one terminal audit;
9. injected boss activation rejection **after durable Beat 3**: `FAILED|BOSS_ACTIVATION_FAILED`; no boss runtime, reward or extra terminal audit;
10. duplicate death callback and stale post-cleanup event replays: state remains terminal with no re-spawn, beat progress, extra audit or reward mutation;
11. PostgreSQL replay/reconciliation on a V9-attached completed run plus repeated player `/boss recover`: precisely one shard per successful real client, no second item intent and no duplicate 250-COINS economy grant.

All real-client **positive** combat E2Es retain normal client-originated movement/attacks and server-confirmed damage. RCON fault injection is confined to an independent, CI-only Paper helper, built outside the production core JAR, guarded by an E2E-only environment variable, and executed **after** the positive combat/reward acceptance. Neither the positive combat requirements nor the frozen reward values were weakened.

## Frozen invariants

1. Exactly one canonical `encounter_run` and one V9 activity progress row represent one Stone Vault attempt; the final boss must not open a parallel run.
2. The durable V9 beat is monotonic; semantic replay is idempotent and conflicting/out-of-order transitions fail closed.
3. Only current-run tagged wave mobs count; stale/foreign events cannot advance the activity.
4. Party admission and eligibility are server-owned; unavailable, disconnected, dead or out-of-arena players cannot silently change durable canonical membership.
5. Failed and abandoned attempts cannot grant the frozen currency or shard rewards.
6. Reward identity, amount, currency transaction, V8 item intent, item snapshot, delivery token and recovery behavior remain Phase 4C1/4C2-owned and exactly once.
7. Wave entity placement is supported/terrain-aware; unreachable or unsupported sites must not produce an unfinishable active wave.
8. Restart abandons active attempts; v1 does not reconstruct live wave entities or reanimate terminal runs.
9. Real-client positive solo/party Stone Warden, full Stone Vault, PostgreSQL, market and crossplay regressions remain required acceptance gates for future phases.
10. The E2E fault helper is **not** a production plugin capability; no player/RCON fault command is shipped in the production core artifact.

## Explicit non-goals

This freeze does not add paid dungeon access, a second game mode, separate world/server shards, queue infrastructure, procedural dungeon generation, new item/currency rewards, revive purchases, randomized paid loot or a general loot catalog. Such changes require an independent design and compatibility review.

## Next gate

Proceed to **Phase 5 — Commerce and Cosmetics**, beginning with a docs-first policy and entitlement-domain review. Do not modify frozen encounter, economy, reward or item-delivery contracts to implement purchase benefits. Real-money checkout and payment-provider credentials are **out of scope** until a specific audited design, disclosure obligations and exactly-once purchase-delivery acceptance have been approved.
