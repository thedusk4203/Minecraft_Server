# Technical Architecture — v0.1

## Goals
- Reproducible local, staging and production environments.
- Java + Bedrock crossplay.
- Safe iteration through CI, automated tests and staged deployment.
- Start simple; scale only when telemetry proves a need.
- Keep player data durable and recoverable.
- Prefer observable systems over opaque manual configuration.

## Initial topology

```text
Internet
  |
  v
Velocity proxy
  |
  +--> Lobby (optional in earliest local development)
  |
  +--> Survival / RPG Paper server
          |
          +--> Core plugin(s)
          +--> Geyser/Floodgate integration path
          +--> PostgreSQL
          +--> Redis only when a concrete cross-process need exists
          +--> metrics/logging
```

## Runtime choices
### Minecraft server
- Paper-compatible server runtime for the gameplay server.
- Velocity for proxying once multiple backend servers or a dedicated lobby justify it.
- Geyser + Floodgate for Bedrock interoperability.

Exact versions must be pinned during implementation after checking current upstream compatibility. Agents must not invent version combinations.

## Code strategy
Start with a small number of cohesive modules rather than dozens of unrelated custom plugins.

Suggested logical boundaries:

```text
core/
  identity
  persistence
  permissions adapters
  events
  telemetry

economy/
  wallets
  transactions
  market adapters

progression/
  skills
  jobs
  objectives
  quests

content/
  items
  encounters
  bosses
  dungeons

social/
  land
  groups
  chat integrations

commerce/
  entitlements
  cosmetics
  supporter ranks

admin/
  moderation
  audit log
  diagnostics
```

These may initially live in one deployable plugin if that reduces complexity. Module boundaries are architectural, not a requirement to create many JARs.

## Persistence rules
- PostgreSQL is the source of truth for custom durable progression and commerce data.
- Every currency mutation must be represented as an auditable transaction, not only as a mutable balance.
- Idempotency is required for purchase delivery and retryable external events.
- Schema migrations are version-controlled.
- Player identity must account for Java/Bedrock UUID differences introduced by the crossplay stack.
- No production state should depend solely on in-memory caches.

## Reliability
Required before public launch:
- Automated world and database backups.
- Documented restore drill.
- Graceful shutdown handling.
- Restart-safe jobs/objectives.
- Duplicate-delivery protection.
- Data validation on plugin startup.
- Health checks for dependencies where practical.

## Observability
Collect at minimum:
- online/concurrent players
- joins/leaves
- Java vs Bedrock client path
- onboarding funnel events
- session duration
- TPS / MSPT
- heap and GC indicators
- plugin exceptions
- database latency/errors
- economy mint/sink flows
- quest/objective completion
- purchases/entitlement delivery status

Logs should be structured where practical and include correlation identifiers for commerce and administrative actions.

## Environments
### Local
- Developer machine.
- Disposable test worlds.
- Seeded fixtures.
- Fast startup.

### Staging
- Mirrors production configuration closely.
- Synthetic/test accounts only where payment systems are involved.
- Used for migrations, content releases and load tests.

### Production
- Manual approval gate for consequential releases initially.
- Secrets never stored in Git.
- Backups and monitoring enabled.

## Deployment model
Implementation target:

```text
commit / pull request
  -> compile
  -> static checks
  -> unit tests
  -> integration tests
  -> start ephemeral Paper test server
  -> smoke tests
  -> build artifact

merge to main
  -> staging deployment
  -> smoke test
  -> manual approval initially
  -> production deployment
```

## AI-agent operating model
AI agents may:
- write code/config/docs/tests
- inspect logs and metrics
- create migrations
- propose balance changes
- generate content definitions
- run deterministic validation

AI agents must not autonomously:
- deploy unreviewed economy changes to production
- grant or revoke paid entitlements without an auditable workflow
- permanently ban players based only on probabilistic inference
- modify production backups
- change monetization policy
- rotate/delete secrets without explicit authorization

## Security baseline
- Least-privilege service accounts.
- Secrets via environment/secret manager, never committed.
- Admin commands audited.
- Database not exposed publicly unless strictly required and protected.
- Rate-limit externally reachable administrative endpoints.
- Treat player-controlled text, commands, NBT and plugin messages as untrusted input.

## Scaling strategy
Do not pre-scale.

Phase 1: one survival backend on high-clock CPU.
Phase 2: introduce dedicated lobby/proxy if operationally useful.
Phase 3: move heavy/isolatable activities to separate backend servers only after profiling.
Phase 4: shard worlds or services only after CCU/data shows the bottleneck.

The architecture should preserve a path to scale without paying the complexity cost before it is needed.
