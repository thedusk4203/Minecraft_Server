# Phase 3 freeze — first playable loop

Status: **FROZEN / COMPLETE**

Frozen implementation base: `8657de3c1116e95f051f04f465d686f8d8c39790`

Merge-state CI: **41017540269 — SUCCESS**

## Exit gate

Phase 3 is frozen because a normal player can now complete the first self-directed survival progression loop without staff intervention:

1. join and receive durable-state-driven guidance;
2. claim protected land through GriefPrevention;
3. level an AuraSkills skill;
4. receive the one-time `100 COINS` starter reward through the PostgreSQL economy ledger;
5. inspect and use the player market;
6. complete one player-to-player trade with crash-safe seller escrow, atomic payment and retry-safe buyer delivery.

The market milestone is recorded only after terminal item delivery, not merely after listing creation or payment.

## Slice receipts

### Phase 3A — onboarding + first land claim

- PR #4
- merge SHA: `eab159695157a811abb987e7f8a23e56bf9eac0c`
- final-head CI: `41000886983` — SUCCESS

### Phase 3B — first skill level + starter currency

- PR #5
- merge SHA: `bb28cbf133fc0a4ef203a1147ce9402b440d5249`
- final-head CI: `41002439944` — SUCCESS

### Phase 3C1 — atomic P2P settlement foundation

- PR #6
- merge SHA: `96f756f5e20caaf1151d484110c40e2b1bcfa243`
- final-head CI: `41003473192` — SUCCESS

### Phase 3C2a — seller escrow + listing

- PR #7
- merge SHA: `8ccfa391cdc1ef86194b9795f4fff3066d7d4b41`
- final-head CI: `41013473040` — SUCCESS

### Phase 3C2b — buyer payment + crash-safe delivery

- PR #8
- merge SHA: `8657de3c1116e95f051f04f465d686f8d8c39790`
- final-head CI: `41016015451` — SUCCESS
- merge-state CI: `41017540269` — SUCCESS

## Frozen correctness boundaries

- PostgreSQL remains authoritative for custom durable progression and money state.
- Economy mutations remain ledger-backed and idempotent.
- Seller escrow uses durable PDC token ordering around `Player.saveData()` and PostgreSQL listing state.
- Buyer payment, purchase creation and listing reservation commit atomically in PostgreSQL.
- Buyer delivery remains a durable saga because Bukkit inventory cannot participate in the database transaction.
- Crash/retry cannot legitimately charge a winning buyer twice or deliver the same escrow snapshot twice.
- Concurrent buyers serialize on the listing row so one listing has at most one buyer.
- `market_used_at` is written only when delivery reaches terminal `COMPLETED`.
- Java and Bedrock continue to share the same server-authoritative gameplay path through Velocity/Geyser/Floodgate.

## Known deliberate limitations

These are not Phase 3 blockers:

- the market has no bidding, price history, advanced search or GUI auction house;
- paid delivery waits in `DELIVERY_PENDING` when the buyer inventory is full;
- corrupted raw item snapshots fail closed for staff recovery rather than silently minting replacement value;
- `completed_at` in onboarding remains reserved for a later explicit completion policy;
- content depth is intentionally small; Phase 3 proves the playable progression loop and its reliability boundaries, not long-term PvE retention.

## Next gate — Phase 4A

Start **Encounter Runtime Foundation** as a separate implementation slice.

Scope should be limited to:

- data-driven encounter definition validation;
- durable encounter run identity and lifecycle;
- participant tracking sufficient for later party encounters;
- attempt/completion/failure telemetry;
- restart-safe state handling;
- audit/diagnostic visibility;
- deterministic tests for lifecycle/idempotency.

Do **not** add the first boss, dungeon reward economy or complex mechanics in 4A. Those should consume the framework in later reviewable slices after the runtime lifecycle is proven.
