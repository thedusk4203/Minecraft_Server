# Core platform invariants

Phase 2 turns the runnable server skeleton into a durable platform. This document defines the invariants implementation agents must preserve.

## Identity

Minecraft UUIDs are external identifiers, not primary business identities. Each player receives an internal immutable `player_id` UUID. This prevents future Java/Bedrock identity details from leaking through every gameplay table.

`platform` begins as `UNKNOWN` unless the server has authoritative platform evidence. Do not infer Bedrock identity from username prefixes. A later Floodgate-backed adapter may promote an identity to `BEDROCK` and attach a platform-specific subject.

## Persistence

PostgreSQL is authoritative for durable player and economy state. Schema changes are Flyway migrations stored under `src/main/resources/db/migration`.

A migration already applied to a shared environment must never be edited in place. Add a new migration instead.

## Permissions

Core authorization uses named Bukkit permission nodes rather than hard-coded operator checks or direct coupling to one permission plugin. The Phase 2 administrative node is `minecraftserver.admin` and defaults to operators in `plugin.yml`.

Permission providers such as LuckPerms can grant or revoke these nodes through the normal Bukkit permission interface. Core code checks `CommandSender.hasPermission(...)`; it does not mutate external groups or ranks directly. A dedicated provider adapter should only be added when the product needs programmatic group management rather than ordinary authorization checks.

## Economy

Currency uses integer minor units (`BIGINT`), never floating point.

`economy_account.balance` is the current materialized balance. Every mutation must occur in the same database transaction as an immutable `economy_transaction` ledger row.

Required invariants:

- balances may not become negative;
- zero-delta ledger entries are invalid;
- every mutation has a globally unique idempotency key;
- retrying the same idempotency key returns the original result rather than applying the mutation again;
- account mutation is serialized with a row lock;
- ledger rows are never edited to correct mistakes; use compensating transactions;
- `balance_after` must equal the committed account balance after that ledger entry.

## Audit

Consequential mutations emit an `audit_event` with a correlation ID. Correlation IDs connect a user action or automated operation to the records it produced.

Audit payloads are JSONB and must not contain credentials, access tokens, private keys, or raw payment data.

Administrative `mcserver` commands emit `admin.command` audit events. When the sender is a known player, the event stores the internal `player_id`; console/RCON actions remain attributable through the correlation ID and surrounding server logs.

## Admin diagnostics

`/mcserver health` is intentionally a synchronous, non-blocking liveness command so RCON and container smoke tests receive an immediate response. It reads Minecraft process state and Hikari pool state only; it does not open a JDBC connection on the Minecraft main thread. Its audit write is dispatched to the persistence executor.

`/mcserver diagnostics` performs the deeper durable-state query asynchronously. It reports known players, economy accounts, economy transactions, audit-event count and connection-pool activity, and records the diagnostic action in the same database transaction as the snapshot query.

Use `health` for fast liveness and `diagnostics` for a database-backed administrative snapshot. A successful plugin startup already proves initial database connectivity and migration success; ongoing database failures surface through diagnostics and persistence operations.

## Runtime failure policy

The core plugin is stateful. If its database cannot initialize or migrations fail during startup, the plugin must fail closed rather than silently running in a partially durable mode.

Normal gameplay code must not block the Minecraft main thread on database I/O. Capture Bukkit state on the owning thread and perform JDBC work on the persistence executor.

## Recovery

Phase 2 migrations are forward-only in normal operation. Recovery from a bad migration means restoring the pre-migration database backup or applying a reviewed corrective migration; destructive automatic rollback is not allowed.
