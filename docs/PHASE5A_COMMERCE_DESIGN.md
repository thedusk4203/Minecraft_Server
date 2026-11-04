# Phase 5A Design — Commerce Policy and Entitlement Foundation

Status: **PROPOSED / DESIGN ONLY — NOT AUTHORIZED FOR IMPLEMENTATION**

Dependency gate: Phase 4D Stone Vault must remain frozen on exact verified merge-state CI and `docs/PHASE4D_FREEZE.md`. This document may be reviewed before the receipt PR merges; **no commerce runtime or payment-provider integration is permitted until the design receives an explicit readiness review**.

## Objective

Enable the survival/RPG server to fund hosting and ongoing development through optional, noncompetitive **cosmetics and supporter identity** while preserving the free player experience, frozen economy/encounter rewards, Java/Bedrock parity and crash-safe PostgreSQL authority.

This phase does **not** choose a payment processor, enable live purchases, ask players for money or establish a final price catalog. It defines the minimum compliant product boundary and transaction/entitlement contract for later testable implementation.

## Primary policy references (reviewed 2026-11-04)

- [Minecraft EULA](https://www.minecraft.net/en-us/eula)
- [Minecraft Usage Guidelines — Servers and Hosting](https://www.minecraft.net/en-us/usage-guidelines)
- [Minecraft Community Standards](https://www.minecraft.net/en-us/community-standards)

These policies may change. Review the latest official text again before public launch, every substantive commerce change and periodically thereafter. This design is a conservative engineering interpretation, **not** an assertion of Mojang/Microsoft endorsement or legal advice. Any uncertainty about local consumer protection, taxes, age/privacy requirements or payment regulation requires appropriate specialist review before live payment acceptance.

### Operational and product constraints

1. **Legitimate game access:** the public entry point must require authentic Minecraft access. The current dev `config/velocity/velocity.toml` specifies `online-mode = true` and modern forwarding; backend Paper is intentionally offline-mode behind the proxy. A production readiness gate must independently verify authenticated Velocity/Floodgate access, forwarding secrets, firewalling and **no publicly reachable backend**. CI's offline Mineflayer E2E clients are test fixtures, never a commercial access policy.
2. **No brand impersonation:** do not market the server as official, endorsed or partnered with Mojang/Microsoft. Provide prominent independent-server disclaimer, identifiable operator and an actual purchase-support contact (not only a chat/forum link).
3. **Public pricing before entry:** publish the full catalog, price, benefit, duration and purchase terms in a publicly accessible online page before a customer signs in or joins; keep a copy of the policy/version accepted for each order.
4. **Online purchase history:** each paying user must have access to an online history of purchases for real money, including provider transaction reference, SKU/version, charged amount/currency, paid/refunded status and delivery state. Avoid displaying another player's private payment details.
5. **All-ages appropriate:** no gambling or paid randomized loot boxes, adult/mature product promotions, questionable branded content, exploitative dark patterns or undisclosed influencer promotion.
6. **No paid capes:** do not sell cape-like cosmetics or features that imply a Minecraft player cape.
7. **Competitive integrity:** Mojang's guidelines can permit some gameplay-affecting entitlements under constraints, but this project's v1 policy is **strictly narrower**: no purchasable stat, combat, durability, XP, claim, economy, auction, movement, dungeon, matchmaking or drop-rate advantage.
8. **Virtual money separation:** the frozen `COINS` gameplay currency stays an earned internal ledger, with **no** cash-out, real-money conversion, paid exchange rate or cross-server transfer. Phase 5A does not sell in-game currency.
9. **Donation distinction:** an unconditional donation must not confer a donor-only benefit. A paid cosmetic/supporter badge is a clearly disclosed **entitlement purchase**, not presented as a donation; a server-wide donation goal may benefit everyone if policy-compatible.
10. **Payment privacy:** use a suitable regulated external payment service for future card processing; server databases must never hold full card data, CVV, bank credentials or client-reported `payment_success` flags. Retain minimum necessary purchaser identifiers and fulfill privacy/deletion obligations.

## V1 product policy proposal

Keep the server **free to join**. All core survival play, land protection, market access, the Stone Vault expedition, Stone Warden and its frozen 250-COINS/shard rewards remain attainable under identical gameplay conditions for paying and nonpaying players.

First potential catalog categories (examples to review, not final inventory or pricing):

| Category | Permitted candidate | Noncompetitive boundary |
| --- | --- | --- |
| Supporter identity | Optional supporter badge/title | No priority queue, command permissions, resource generation or economy access |
| Chat cosmetics | Colored prefix or text decoration | Rate limited, accessible, does not impersonate staff or alter moderation visibility |
| Ambient effects | Opt-in particle or harmless celebration effect | Performance and visibility caps; no PvP visibility/stealth/combat benefit |
| Social emotes | Cosmetic emote or reaction | Java/Bedrock parity, no collision/hitbox or movement advantage |
| Server-wide milestones | Community cosmetic event unlock on published goal | Same benefit for all eligible players, independent of individual payment |

**Explicit exclusions for v1:** paid items/armor/weapons, combat buffs, premium dungeon keys, faster boss reward recovery, increased claim size where scarcity matters, paid market slots or reduced taxes, exclusive gameplay mods, real-money COINS, loot boxes, cape imitations and time-limited FOMO mechanics aimed at minors.

Any proposed benefit must pass a documented **benefit-level competitive-impact assessment** before it can be listed.

## Authority boundaries

The commerce subsystem must never mutate or derive purchase eligibility from the frozen `encounter_run`, `structured_activity_run`, `encounter_reward`, `encounter_reward_item` or `economy_transaction` tables.

Canonical player ownership is the existing `player_identity.player_id` UUID. Java and Floodgate/Bedrock platform subjects must remain distinguishable and may not be merged merely because two display names match. Linking accounts requires explicit proof of both accounts and an audited, reversible linking policy in a later review.

The source of truth for a paid cosmetic is a **durable entitlement record**, not a Bukkit permission node, chat prefix, client-side receipt or payment-webhook payload alone. Gameplay renders a derived, revocable cosmetic view of this authority.

## Proposed durable data model (design, not an approved migration)

Future schema name/version to be selected during implementation readiness (no new V10 migration in this PR).

- `commerce_catalog_sku`: immutable SKU ID, version, benefit definition hash, `cosmetic_only` classification, public-description version, price snapshot, currency, effective window, retired flag and policy-review receipt.
- `commerce_order`: immutable internal order UUID, purchaser identity, SKU+version, charged amount/currency, public terms version, provider reference, lifecycle state, created/paid/refunded timestamps.
- `commerce_payment_event`: exact provider + event ID uniqueness, verified event signature metadata, event hash, provider payment identity, observed/processed times, replay outcome and sanitized failure reason.
- `commerce_entitlement`: stable internal entitlement UUID, player UUID, benefit ID/version, purchase/order authority, state (`ACTIVE`, `EXPIRED`, `REVOKED`), period, revocation reason and monotonically increasing version.
- `commerce_fulfillment`: exactly-once grant identity derived from purchase/order and entitlement/benefit, processing attempts, delivery acknowledgment, last error, correlation ID and audit keys.
- `commerce_refund`: provider refund identity, order link, refunded amount/currency, verified event ID, revocation decision, applied-at and audit correlation.
- `commerce_audit_event`: immutable journal sufficient to reconstruct payment verification, order state, grant/replay, refund, revocation and administrative actions; redacted to avoid unnecessary personal data.

Proposed key constraints:
- `UNIQUE(provider, provider_event_id)` for webhook delivery;
- `UNIQUE(provider, provider_payment_id)` or a justified partial/typed identity mapping that tolerates legitimate multi-event payment lifecycles;
- `UNIQUE(order_id, benefit_id, benefit_version, target_player_id)` for a single grant identity;
- `UNIQUE(provider, provider_refund_id)` for refunds;
- immutable price/currency/SKU snapshot after order creation; no admin edits to already settled order semantics;
- foreign keys and transactional audit coupled to state changes;
- order and entitlement transition guards rejecting impossible, stale or conflicting event sequences.

All event IDs, external references and entitlement keys must be bounded, validated and treated as untrusted input.

## State and delivery contract

Proposed lifecycle (names and exact transition table to be reviewed):

```
ORDER_CREATED -> AWAITING_PAYMENT -> PAYMENT_VERIFIED
              -> FULFILLING -> FULFILLED
PAYMENT_VERIFIED / FULFILLED -> REFUND_PENDING -> REFUNDED
REFUND_PENDING / REFUNDED -> ENTITLEMENT_REVOKED
AWAITING_PAYMENT -> EXPIRED_OR_CANCELLED
```

No entitlement is granted based on a browser redirect, player command, unverified webhook, manual screenshot or order creation alone.

The external provider authenticates the payer transaction; only a signature-verified and amount/currency/SKU-matched payment event moves an order to verified. The persistence layer acquires a transactional lock on the canonical order, applies immutable semantics and commits the entitlement/grant receipt plus audit atomically.

Webhook delivery is **at least once and out of order**. Processing must be idempotent across retries, duplicate events, concurrent servers and restarts. If a crash occurs after payment verification but before cosmetic application, durable reconciliation eventually applies one grant. If rendering a cosmetic fails because a player is offline, store the entitlement and apply its view on join; do not lose or mint a second grant.

Refund, chargeback, cancellation and subscription expiry (if subscriptions are ever approved) must produce an auditable **revocation**, including clean removal of cached permissions/prefixes. A refund event cannot delete order/payment evidence or silently reverse earned non-commerce gameplay rewards.

## Security and privacy requirements

- Verify webhook authenticity with provider-specific signature validation over the raw request body, timestamp window and key rotation; enforce replay windows, rate limits and request size limits.
- Keep provider secrets in managed secret stores; never commit keys, store them in player-facing configs or print full signed payloads in CI logs.
- Reject client-supplied player IDs/benefit IDs as authority; assign a purchase to a verified player-account linking flow.
- Use least-privilege database roles, separate webhook ingress from Bukkit thread work and enforce bounded retries/dead-letter diagnostics.
- Publish a retention schedule and deletion/export process, distinguishing legally required purchase records from deletable gameplay data.
- Make order/audit records viewable only to purchaser and authorized support. Admin overrides must be separately authenticated, justified and audited.
- Maintain an external-readable purchase history/support-contact channel even during game-server maintenance.
- Audit refund/chargeback handling for minors, consumer rights, invoicing, taxes and applicable local payment rules before real-money launch.

## Acceptance and testing plan

### Design readiness (Phase 5A exit)

- authoritative policy review and recorded reviewed date, disclaimers, public pre-login pricing/history UX mock;
- SKU classification review with explicit evidence that proposed cosmetics confer no competitive advantage;
- documented immutable order/entitlement/refund authority and migration design;
- provider-agnostic signature validation and idempotency specification;
- threat model for forged webhooks, duplicated provider events, admin misuse, identity takeover and account linking;
- mock-only staged purchase, refund, reconciliation and receipt-history plan;
- explicit decision on payment processor, jurisdictions, terms of sale and privacy handling **before** implementation scope approval.

### Proposed implementation gates (future PRs only)

1. PostgreSQL tests for duplicate event ID, duplicate payment, out-of-order delivery, concurrent grants, partial commit/audit conflict rollback and refund replay.
2. Expired SKU, wrong currency/amount, changed catalog definition, invalid signature and forged client success must fail closed.
3. Simulated crash between payment verification and entitlement application, then restart/reconciliation delivers exactly one cosmetic benefit.
4. Offline/online user and Java/Bedrock identities cannot be conflated; relog and server restart do not duplicate or lose entitlements.
5. Refund/chargeback revokes the right cosmetic exactly once without altering the frozen COINS/shard or PvE reward ledger.
6. Local test gateway with **synthetic sandbox transactions**, no live payment credential in CI and a complete purchase-history receipt; collect end-to-end audit evidence.
7. Existing Phase 1–4D market, economy, Paper, Java/Bedrock crossplay, solo/party Stone Warden, Stone Vault positive/failure-path and item-recovery gates must remain green on exact implementation head and merged main.

## Proposed implementation slices (not started)

- **5A — Product/policy/entitlement design:** this document, review evidence, formal schema/contract and acceptance plan.
- **5B — Durable commerce authority:** migration and provider-neutral order/entitlement state machine, no live payments.
- **5C — Cosmetic projection:** safe, client-parity cosmetic rendering derived from entitlements and reversible on refund.
- **5D — Mock purchase and recovery:** synthetic webhook events, purchase history, refund and full E2E.
- **5E — Live-provider readiness:** documented policy/privacy/tax/payment review, secret operations, risk and rollback gates. No live provider before approval.

## Explicit non-goals

No payment gateway connection, currency sale, pay-to-win perk, cape sale, unreviewed supporter tier, real-price promise, public shop launch, new dungeon drops, gameplay balancing, subscription billing, refund API or V10 migration is implemented by this design. Any Phase 5 changes to frozen Phase 4A–4D interfaces require a separately reviewed compatibility change.
