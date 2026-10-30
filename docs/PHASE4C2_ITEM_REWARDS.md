# Phase 4C2 — Crash-safe Stone Warden Item Reward Delivery

Status: implementation in review

## Goal

Extend the frozen Phase 4C1 Stone Warden reward contract with one concrete item reward while preserving exactly-once recovery semantics across Bukkit playerdata and PostgreSQL, which do not share a transaction manager.

The first item definition is intentionally narrow:

- key: `stone_warden_shard`
- version: `1`
- material: `AMETHYST_SHARD`
- amount: `1`
- display name: `Stone Warden Shard`

This phase does not introduce random loot or a generic loot-table framework.

## Durable authority

Phase 4C1 `encounter_reward` remains unchanged and remains the authority for completed-run participant eligibility and the 250-COINS grant.

V8 adds `encounter_reward_item`, one-to-one with the existing reward ID. The item delivery token is constrained to equal `reward_id`, so retry/restart never invents a second delivery identity for the same participant reward.

Only a `CURRENCY_GRANTED` Phase 4C1 reward attached to a durable `COMPLETED` Stone Warden run can materialize an item intent.

## State machine

`PREPARED`

- durable item identity exists;
- item semantics are frozen in PostgreSQL;
- no Bukkit item snapshot exists yet.

`DELIVERY_PENDING`

- exact marked Bukkit `ItemStack` bytes and SHA-256 are durable;
- delivery may be absent from inventory or may already be present with its PDC marker;
- recovery must inspect the stable marker before restoring anything.

`DELIVERED`

- marked item was persisted to playerdata before this transition;
- terminal acknowledgement and audit are durable;
- any remaining PDC marker is cleanup-only and must be stripped without minting another item.

## Ordering contract

The normal path is:

1. Phase 4C1 currency reward reaches durable `CURRENCY_GRANTED`.
2. Insert/replay the V8 `PREPARED` item intent.
3. On the Bukkit main thread, create the marked item using `reward_id` as the delivery token.
4. Serialize it and persist the exact snapshot + SHA-256, advancing to `DELIVERY_PENDING`.
5. Inspect player inventory.
   - zero matching markers: restore the persisted snapshot into one empty slot;
   - exactly one matching marker: reuse that item;
   - more than one marker: fail closed.
6. Call `player.saveData()` while the item is still marked and protected.
7. In PostgreSQL, atomically advance `DELIVERY_PENDING -> DELIVERED` and append the terminal audit event.
8. Strip the PDC marker and best-effort save playerdata again.

The DB terminal transition is therefore impossible on the intended path before marked playerdata is durable.

## Crash windows

### Before snapshot persistence

DB remains `PREPARED`; inventory has not been mutated. Recovery recreates and persists the snapshot.

### After snapshot persistence, before inventory mutation

DB is `DELIVERY_PENDING`, marker count is zero. Recovery deserializes the persisted snapshot and delivers it.

### After inventory mutation, before playerdata save

DB is `DELIVERY_PENDING`. A process crash may discard the unsaved inventory mutation; recovery sees zero or one durable marker and converges safely.

### After playerdata save, before DB acknowledgement

DB is `DELIVERY_PENDING`, but playerdata contains exactly one marked item. Recovery reuses the marker and acknowledges delivery instead of minting another item.

### After DB acknowledgement, before marker cleanup/save

DB is `DELIVERED`. Recovery strips the stale marker; it never restores another reward item.

## Fail-closed cases

Automatic delivery stops for the affected reward when it observes:

- duplicate delivery markers;
- a marker owned by another internal player;
- an orphan marker with no durable V8 row;
- item-key/version/material/amount drift;
- payload SHA-256 mismatch;
- a marked live item whose expected item semantics do not match the frozen definition;
- invalid state transitions.

The marker stays protected so staff can inspect durable diagnostics instead of silently deleting or duplicating the item.

## Recovery surfaces

- player join;
- player respawn;
- periodic online reconciliation after the existing Stone Warden currency reconciler;
- explicit `/boss recover`.

Full inventory is not an error: the durable item remains pending until an empty slot exists.

## Observability

`/mcserver diagnostics` reports:

- total and pending `encounter_reward` rows;
- total and pending `encounter_reward_item` rows.

Item preparation, snapshot persistence, delivery, marker cleanup and conflict paths emit `BOSS_REWARD_TELEMETRY` messages. Durable lifecycle transitions are also written to `audit_event`.

## Verification gate

Phase 4C2 is not complete until all of the following are green on one exact PR head:

- Gradle build/unit tests;
- V8 packaged in the shaded plugin JAR;
- PostgreSQL integration tests including rollback and concurrency paths;
- Paper/Flyway full-stack boot through V8;
- existing market/crossplay/gameplay regression checks;
- solo real-client Stone Warden completion with the shard visible in the client's inventory and one durable `DELIVERED` item row;
- two-client party completion with the shard visible in both client inventories and exactly two durable `DELIVERED` item rows;
- all frozen Phase 4A/4B/4C1 checks remain green.
