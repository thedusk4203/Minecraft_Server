# AGENTS.md

## Mission
Build and operate the Minecraft server defined in `docs/PRODUCT.md` without silently changing product direction.

## Source of truth
Read these before implementation:
1. `docs/PRODUCT.md`
2. `docs/GAME_DESIGN.md`
3. `docs/ARCHITECTURE.md`
4. `docs/ROADMAP.md`

If documents conflict, stop and surface the conflict rather than guessing.

## Engineering rules
- Prefer small, reviewable changes.
- Add tests for important behavior and every bug fix where practical.
- Never commit secrets, credentials, private keys, production tokens or database passwords.
- Pin compatibility-sensitive versions after checking upstream documentation.
- Do not invent APIs, versions or plugin behavior.
- Prefer deterministic configuration and scripts over undocumented manual steps.
- Durable player/economy state must survive restart and reconnect.
- Currency and paid entitlement operations must be auditable and idempotent.
- Avoid premature distributed architecture.
- Profile before optimizing.

## Product guardrails
Do not add major game modes outside the MVP scope without an explicit owner decision.
Do not introduce pay-to-win mechanics merely to improve monetization.
Do not add gambling/loot-box monetization without explicit policy and compliance review.
Do not split the player population without evidence that concurrency can support it.

## AI autonomy boundaries
Agents may autonomously:
- implement scoped code and tests
- improve docs
- create local tooling
- propose designs and balance changes
- inspect logs and diagnose issues
- generate candidate content definitions

Agents must request/require owner approval before:
- production deployment of consequential gameplay/economy changes
- permanent player bans from ambiguous evidence
- destructive production database operations
- changes to monetization policy or pricing
- deleting backups
- changing security-sensitive access controls

## Definition of done for code changes
A change is not done until, where applicable:
- it builds cleanly
- automated tests pass
- configuration is documented
- failure paths are considered
- logging/diagnostics are adequate
- migrations are reversible or have a documented recovery path
- acceptance criteria from the relevant roadmap phase are satisfied

## Pull request expectations
PR descriptions should include:
- problem / objective
- scope
- implementation summary
- test evidence
- risks
- rollback/recovery notes when relevant
- follow-up work

## Repository direction
The project starts as one cohesive product. Do not create many independent services or JARs unless a concrete operational or ownership boundary justifies them.
