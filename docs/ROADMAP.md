# Roadmap — MVP v0.1

## Phase 0 — Product foundation
Status: complete

Deliverables:
- Product specification
- Game design foundation
- Technical architecture
- Agent operating rules
- MVP acceptance criteria

Exit gate:
- Scope is narrow enough that an implementation agent can work without inventing product direction.

## Phase 1 — Local technical skeleton
Status: complete

Deliverables:
- Repository layout
- Paper-compatible plugin project
- Local development environment
- PostgreSQL development database
- Java + Bedrock crossplay proof
- Basic logging and health diagnostics
- CI build/test workflow

Exit gate:
- A clean checkout can be built and a local test server can be started reproducibly.
- Java and Bedrock test clients can join.

## Phase 2 — Core platform
Status: complete

Deliverables:
- Player identity abstraction
- Persistence layer and migrations
- Audit/event logging
- Permissions integration
- Economy transaction ledger
- Admin diagnostics

Exit gate:
- Restart/reconnect tests preserve player state.
- Economy mutations are auditable and duplication-safe.

## Phase 3 — First playable loop
Status: complete

Deliverables:
- Onboarding
- Land protection integration
- Currency loop
- Skills/jobs baseline
- Trading/market baseline
- Starter objectives/quests

Exit gate met:
- A new player can join, understand the current objective, claim land, earn starter currency and complete a first player-to-player trade without staff intervention.
- Durable onboarding state drives reconnect guidance.
- Money settlement is auditable and idempotent.
- Seller escrow and buyer delivery survive retry/restart without intentionally duplicating money or the escrowed item.
- Java and Bedrock share the same server-authoritative path.

Freeze receipt: `docs/PHASE3_FREEZE.md`.

### Phase 3A — Onboarding + first land claim
Status: complete

Receipt:
- PR #4 merged at `eab159695157a811abb987e7f8a23e56bf9eac0c`.
- Final-head CI `41000886983` succeeded.

### Phase 3B — First skill level + starter currency
Status: complete

Receipt:
- PR #5 merged at `bb28cbf133fc0a4ef203a1147ce9402b440d5249`.
- Final-head CI `41002439944` succeeded.

### Phase 3C — First trade / market checkpoint
Status: complete

#### Phase 3C1 — Atomic P2P settlement foundation
Status: complete

Receipt:
- PR #6 merged at `96f756f5e20caaf1151d484110c40e2b1bcfa243`.
- Final-head CI `41003473192` succeeded.

#### Phase 3C2a — Seller item escrow + listing
Status: complete

Receipt:
- PR #7 merged at `8ccfa391cdc1ef86194b9795f4fff3066d7d4b41`.
- Final-head CI `41013473040` succeeded.

#### Phase 3C2b — Purchase settlement + buyer delivery + first trade
Status: complete

Receipt:
- PR #8 merged at `8657de3c1116e95f051f04f465d686f8d8c39790`.
- Final-head CI `41016015451` succeeded.
- Merge-state CI `41017540269` succeeded.

## Phase 4 — PvE content
Status: in progress

Deliverables:
- Encounter framework
- First boss
- First dungeon/equivalent structured PvE activity
- Reward tables
- Encounter telemetry

Exit gate:
- A small party can complete the full PvE loop repeatedly without duplication/data-loss bugs.

### Phase 4A — Encounter Runtime Foundation
Status: complete / frozen

Frozen scope:
- V6 durable `encounter_run` and `encounter_participant` state
- resource-driven encounter definitions with strict startup validation
- canonical semantic SHA-256 definition fingerprints stored with every run
- lifecycle `ACTIVE -> COMPLETED | FAILED | ABANDONED`
- start idempotency keyed by globally unique correlation ID
- canonical participant membership persistence
- row-locked terminal transitions and one terminal audit identity per run
- startup recovery of stale ACTIVE rows as `ABANDONED / SERVER_RESTART`
- encounter audit telemetry and administrative run counts
- Flyway/Paper classloader hardening and shaded-JAR resource verification
- validation/replay/concurrency/restart/failure-injection test coverage

Exit gate met:
- the synthetic `framework_probe` definition validates and the runtime can persist/terminate runs deterministically;
- invalid definitions fail closed;
- exact lifecycle replay is idempotent and semantic conflicts fail closed;
- concurrent conflicting terminal transitions serialize to one winner;
- unresolved runs have explicit restart semantics;
- run/participant telemetry is durable and auditable;
- transaction-tail audit conflicts roll back preceding run state;
- V6 and encounter resources are present in the runtime JAR;
- full PostgreSQL/Paper/gameplay/market/crossplay stack remains green after merge.

Receipt:
- PR #10 merged at `f2f2d8704bb0cbfb542b0b04f64827e69f0b224c`.
- Final-head CI `41020474477` succeeded.
- Merge-state CI `41020981130` succeeded.
- Freeze receipt: `docs/PHASE4A_FREEZE.md`.
- Design detail: `docs/ENCOUNTER_RUNTIME.md`.

### Phase 4B — First Boss Encounter Mechanics
Status: next

Scope:
- one real boss definition consuming the frozen encounter runtime
- explicit start eligibility and participant capture
- controlled boss spawn/despawn ownership
- readable server-side telegraphs compatible with Java and Bedrock clients
- a deliberately small, testable mechanic set
- arena/leash/reset and anti-cheese boundaries
- player death/disconnect/timeout failure handling
- durable run completion/failure wiring
- operational cleanup so no boss entity/run is left orphaned after normal termination
- deterministic pure-logic tests where practical plus full Paper integration smoke

Non-goals for 4B:
- durable loot/reward payout
- economy settlement for rewards
- large dungeon content
- matchmaking/party-system expansion
- multiple bosses or difficulty tiers
- client mods/custom protocol requirements

Exit gate:
- a small eligible group can start one boss encounter and the server owns its lifecycle end-to-end;
- success and failure each terminate exactly one durable encounter run;
- boss/arena cleanup occurs for completion, failure, timeout and plugin/server shutdown boundaries;
- repeated callbacks cannot produce conflicting terminal outcomes;
- Java and Bedrock players receive understandable mechanic cues through server-side primitives;
- existing Phase 1–4A correctness tests remain green.

### Phase 4C — Deterministic boss rewards
Status: planned after 4B

Direction:
- versioned reward-table identity tied to completed boss runs
- exactly-once reward claim/delivery state
- explicit money/item transaction and recovery boundaries
- no reward for FAILED/ABANDONED runs
- retry/restart cannot duplicate grants

## Phase 5 — Commerce and cosmetics
Deliverables:
- Entitlement model
- Cosmetic framework
- Supporter ranks
- Purchase delivery idempotency
- Audit trail
- Policy/compliance review

Exit gate:
- Test purchases can be delivered, retried and reconciled without duplicate grants.

## Phase 6 — Operations
Deliverables:
- Backups
- Restore drill
- Metrics/dashboard
- Alerting
- Moderation baseline
- Discord integration
- Staging deployment

Exit gate:
- Seven-day staging soak without critical manual intervention.

## Phase 7 — Closed alpha
Target:
- >= 20 invited testers

Validate:
- onboarding completion
- session length
- obvious economy exploits
- Java/Bedrock parity
- stability
- first retention signals

No feature expansion is allowed solely because testers request more content. Fix core-loop and reliability issues first.

## Phase 8 — Public MVP
First milestone:
- 20–30 stable concurrent players

Primary metrics:
- D1 / D7 retention
- onboarding completion
- average session duration
- return frequency
- economy inflation/sinks
- error/crash rate
- payer conversion and ARPPU

## Expansion gate
Do not add a second major game mode until:
- the existing mode has stable retention,
- operational burden is under control,
- player density can support splitting the population,
- and telemetry identifies a concrete opportunity.

Candidate expansion after the gate:
- Skyblock
- Prison
- additional survival shards/content

## Immediate next implementation task
Implement **Phase 4B — First Boss Encounter Mechanics** on a fresh branch from the Phase 4A frozen foundation.

Do not add reward delivery in 4B. First prove one actual boss lifecycle, readable mechanics, cleanup and durable completion/failure wiring; then implement exactly-once rewards as Phase 4C.
