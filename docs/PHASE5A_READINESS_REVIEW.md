# Phase 5A Readiness Review — Policy-Safe Commerce and Cosmetic Entitlements

Review date: **2026-11-04** (repository and Minecraft primary-policy sources)

Overall gate: **NOT READY FOR PHASE 5B IMPLEMENTATION — REVIEW/DECISION REQUIRED**

Review scope: Phase 5A *design* in `docs/PHASE5A_COMMERCE_DESIGN.md`, merged as PR #40 at `0a7dff9bc29915434b1495722a0a2d4589255641`. Phase 4D remains frozen by `docs/PHASE4D_FREEZE.md` at runtime merge `49a6675bec70b5ac7fac9aae65680cc68bd9d473` (post-merge CI #208 SUCCESS). This review creates **no** sale, entitlement, code, database migration, price or permission.

## Review method / evidence

Authoritative Minecraft sources, read directly on 2026-11-04:

1. [Minecraft Usage Guidelines — Servers and hosting](https://www.minecraft.net/en-us/usage-guidelines): legitimate purchased clients, allowed monetization categories, no competitive advantage, cape exception, donations, privacy, price disclosure before joining/sign-in, and online real-currency transaction history.
2. [Minecraft EULA](https://www.minecraft.net/en-us/eula): software licensing, plugin/mod terms and distinction between official products and community-created services.
3. [Minecraft Community Standards](https://www.minecraft.net/en-us/community-standards): child-safe and respectful gameplay environment.

**Scope caveat:** Minecraft's Usage Guidelines may be changed or withdrawn; this is a dated *technical design check*, not Mojang/Microsoft approval, local legal advice, tax clearance or permission to process live transactions. Revalidate sources and jurisdiction-specific obligations immediately before public checkout.

Verified repository evidence:

| Source | Observation | Consequence |
| --- | --- | --- |
| `config/velocity/velocity.toml` | `online-mode=true`, `player-info-forwarding-mode="modern"` | Good starting authentication configuration, **not** evidence production firewall/proxy topology is deployed or safe |
| `compose.yaml` | Paper is `ONLINE_MODE=FALSE` behind an unexposed `paper` service; Velocity publishes Java and Bedrock ports | Normal trusted-proxy pattern **only if** backend remains unreachable from untrusted networks; separately verify the real deployed infrastructure |
| `src/main/resources/db/migration/V1__core_platform.sql` | `player_identity.minecraft_uuid` unique; `platform` defaults `UNKNOWN`; `platform_subject` nullable and nonunique | Current canonical `player_id` is durable, but not verified payment account ownership |
| `PlayerBootstrapListener` | Player joins call `identities.recordSeen(minecraftUuid, playerName)` without platform/subject | Normal game bootstrap currently persists `UNKNOWN` platform |
| `PlayerIdentityRepository` | `recordSeen` default uses `UNKNOWN`; conflict upsert updates display name/timestamps only | Existing rows cannot automatically promote platform or validate changed trusted claims |
| `IdentityPlatform` | `JAVA`, `BEDROCK`, `UNKNOWN` exist | Enum alone does not authenticate a platform subject |
| `economy_transaction`, V7/V8/V9 | Immutable-ish game economy, boss rewards and structured activities already frozen | Payment/entitlement authority must **not** reuse those ledgers |
| `docs/PHASE5A_COMMERCE_DESIGN.md` | Proposed provider-neutral data model and cosmetic-only, free-to-join scope | Good starting design; review obligations below must be resolved before migrations, privileges or checkout |

## Policy decisions proposed for approval

For the first monetized release, use a deliberately restrictive profile, even where Minecraft Usage Guidelines might permit broader behavior.

| Product decision | Proposed value | Gate |
| --- | --- | --- |
| Entry | Free for every authenticated owner of a legitimate Minecraft copy | No purchasing condition, queue priority or restricted core-game content |
| Supporter identity | An optional chat/profile badge/title | Cannot impersonate staff or create utility/command permissions |
| Chat decoration | Optional nondeceptive prefix/text color | Spam/visibility moderation and Java/Bedrock presentation parity |
| Ambient celebration | Bounded optional visual effect, initially **disabled** until tested | No hitbox, stealth, visibility, performance or PvP advantage |
| Cosmetic cape | **Prohibited** | Including items designed to visually behave like a player cape |
| COINS, stone_warden_shard, dungeon keys | **Never sold** in v1 | Gameplay and durable rewards stay unchanged |
| Donation | No individual donor-only unlock; server-wide goal optional after review | Do not label an exclusive supporter entitlement as a donation |
| Virtual currency | No real-money COINS price, transfer, exchange or cash-out | Preserve existing standalone gameplay ledger |
| Random paid loot / boosts / competitive perks | **Prohibited** in v1 | No paid gambling/FOMO/exploitation of younger audiences |

This policy is a proposed **product choice**, not an absolute description of what the external Usage Guidelines prohibit. No initial SKUs, price or duration are approved by this review. Price/benefit descriptions and purchase support contacts must be publicly accessible **before** players join or sign in, and each payer must be able to access an online history of real-currency payments.

## Readiness gate matrix

| Gate | Current evidence | Decision / next requirement |
| --- | --- | --- |
| Phase 4D dependency | Freeze receipt merged, CI #208 and #211 SUCCESS | **PASS** — do not reopen PvE/economy |
| Minecraft source-policy check | EULA/Usage Guidelines/Community Standards reviewed 2026-11-04 | **PRELIMINARY PASS** — require independent policy owner signoff and re-check before launch |
| Minimal cosmetics catalog | Candidate categories only, no audited concrete SKU effects | **OPEN** — publish versioned SKU table, detailed benefit semantics and per-feature competitive risk ratings |
| Operator identity, public pricing & contact | Not implemented or evidenced by repository | **BLOCKED FOR LIVE SALES** — approve independent identity, accessible catalog/terms/support page and contact, pre-login |
| Purchaser's online purchase history | Proposed schema only, no buyer-facing portal | **BLOCKED FOR LIVE SALES** — design account-authenticated self-service receipt/history access |
| Genuine-client / proxy trust | Dev Velocity online-mode true; Paper intentionally behind proxy | **VERIFY DEPLOYMENT** — test public port/firewall, proxy forwarding, Floodgate authentication and no direct Paper bypass |
| Verified Java/Bedrock purchase identity | Bootstrap persists `UNKNOWN`; conflict UPSERT never promotes verified platform/subject | **CRITICAL BLOCKER** — Issue #42; no paid entitlement ownership until trusted identity contract and tests exist |
| Event/order/entitlement authority | Entity list and high-level states proposed, but no formal transaction spec | **OPEN** — agree on unique constraints, state transition table, lock/isolation rules, replay semantics and rejection behavior |
| Refund, chargeback and expiry | Conceptual revocation described | **OPEN** — define provider-to-entitlement reversal, post-refund delivery race and audit consistency |
| Jurisdiction/privacy/tax | No recorded operator jurisdiction, PSP, tax or privacy owner | **BLOCKED FOR LIVE SALES** — obtain appropriate local reviews; no assumption of compliance |
| Mock-only CI and audit | Proposed test matrix | **OPEN** — exact finite synthetic event fixtures, concurrency and failure-injection acceptance |
| Production secrets and incident response | General guidance only | **OPEN** — provider key rotation, dead-letter operations, rate limiting, incident runbooks and retention |
| Frozen-gameplay regressions | Full CI includes Java/Bedrock, market, Stone Warden and Stone Vault | **PASS as baseline** — must run on every future commerce PR |

**Current determination:** Phase 5A design has an acceptable direction, but **does not authorize Phase 5B code/migrations** or any public shop. Completing the blockers above or explicitly approving a narrower design-only sandbox scope requires a new recorded decision on Issue #41.

## Required identity sub-gate — Issue #42

Before a paid order can target a `player_id`:

1. Determine the **trusted source** of Java authentication and Floodgate Bedrock identity. Distinguish signed/verified claims from UI names, prefixes and arbitrary player-controlled values.
2. Define uniqueness, immutability and controlled promotion from `UNKNOWN` to `JAVA`/`BEDROCK`; reject conflicting already-verified claims with explicit audit. Migration/backfill must preserve canonical `player_id` and all existing foreign-key references.
3. Define pre-purchase identity eligibility: unverified/UNKNOWN users fail closed for paid purchases and paid grants; this does **not** block normal free gameplay.
4. Keep Java/Bedrock identities separate by default. Linking/gifting is not implicit and needs independent proof of both accounts.
5. Verify public Velocity ports versus internal Paper port at a deployed staging boundary, including secrets and spoofed identity probes.
6. Add deterministic Java/Bedrock, rename, reconnect, replay, conflicting-claims, concurrent login and proxy-bypass tests before enabling paid entitlements.

Do **not** backfill platform using display names, prefixes, heuristics, client-supplied UUIDs or unverified databases.

## Proposed first SKU acceptance contract (illustrative; not for sale)

Only one intentionally simple entitlement should be implemented first **after** approval:

`supporter_title` version 1 (candidate name), permanent cosmetic chat/profile display and nothing else.

- Immutable SKU version and display definition fingerprint.
- No staff-like prefixes, player power, join priority, commands, economy/market benefits, dungeon progression or paid content gate.
- No default effect on players who have not bought it.
- Exactly one durable entitlement for one verified `player_id` and one immutable purchase identity.
- Same permission/render outcome after server restart; no additional entitlement on duplicate fulfillment.
- A verified refund/chargeback disables the cosmetic, even if the player is offline; concurrent late fulfillment cannot re-enable it.
- Java/Bedrock render parity must be tested; if not feasible, do not offer the SKU to the affected platform until parity and disclosure are resolved.
- Purchase history shows exact charged real-currency amount, currency, product/version, timestamp and lifecycle status. None of these values enter the COINS ledger.

No concrete price, term, tax treatment or payment provider is set by this example.

## Proposed Phase 5B contract for review (still **NOT IMPLEMENTATION APPROVED**)

A narrowly bounded provider-neutral PostgreSQL *mock* foundation, separate from Bukkit item/boss/game-economy code:

**Table authority and uniqueness design questions**

- Catalog: `(sku_key, version)` immutable; benefit hash, currency/price and public terms version snapshotted **into each created order**.
- Order: one internal UUID, purchaser `player_id`, SKU-version, amount, currency, lifecycle, created/settled times; do not accept client-reported payment state.
- Provider event: `(provider, event_id)` unique, canonical verified event digest, signature verification result, bounded redacted metadata, process-at time. On replay with same ID but conflicting payload: **reject and audit**, never silently treat as success.
- Provider payment reference: choose a `(provider, payment_id)` transactional authority; process distinct lifecycle events (capture, refund, dispute) without incorrectly declaring the entire payment event unique forever.
- Grant: stable `(order_id, benefit_key, benefit_version, target_player_id)` unique; order lock/compare-and-set + entitlements + audit in one PostgreSQL transaction; no privilege grant based solely on a webhook receipt.
- Refund and reversal: `(provider, refund_id)` unique; refund state and entitlement revocation transactionally bound; stale capture/retry cannot override terminal revocation. Partial refund policy must be defined or rejected at v1.
- Entitlement: durable state `ACTIVE|REVOKED|EXPIRED` and monotonic version. Projection into cosmetic UI is **derived and refreshable**, never source of truth.

**Negative and concurrency acceptance cases**

1. Invalid/missing signature; wrong amount, currency, SKU version or recipient; duplicate conflicting event ID.
2. Two concurrent identical verified events, out-of-order capture/refund and replay after crash.
3. Crash before/after DB commit and before in-game cosmetic projection; exactly one benefit eventually reflected.
4. Refund concurrent with delivery: revocation wins, no late regrant, exactly one audit.
5. Expired/retired SKU and wrong public terms version cannot be paid under silently changed terms.
6. UNKNOWN or conflicting platform subject cannot receive a purchased entitlement.
7. No writes to `economy_account`, `economy_transaction`, V6 encounter, V7 reward, V8 item or V9 progress rows.
8. Mock receipts available only to verified purchaser/support role, with no bank/card secrets in logs.

**Implementation sequencing if subsequently approved:** first formal schema/state machine design and transactional tests with only synthetic internal orders; next trustworthy identity qualification; next read-only entitlement view; then safe cosmetic projection and refund; only after public-shop/privacy/provider review may live payment ingress be considered.

## Decisions explicitly awaiting approval

The responsible project operator/reviewer must approve or revise:

- first SKU scope and concrete competitive-impact review;
- verified Java/Bedrock identity contract, including whether UNKNOWN identities can be upgraded safely (Issue #42);
- shop operator identity, contact, privacy/terms, country and payment-processing arrangements;
- order/entitlement/refund state machine, unique keys and replay strategy;
- mock-only phase boundaries and exact acceptance tests.

Until those decisions are recorded, mark **Phase 5A: DESIGN MERGED / READINESS BLOCKED**, **Phase 5B: NOT STARTED**, and **live payments: PROHIBITED**.

## Next actions

1. Review/resolve Issue #42, specifying trusted identity verification and migration without changing frozen PvE or COINS.
2. Draft a concrete single-SKU `supporter_title v1` UX + policy matrix and accessible pre-login catalog/history mock (no live purchase).
3. Complete provider-neutral state transition/SQL design under a separate docs-only PR; record threat model and acceptance case IDs.
4. Obtain explicit technical/product/policy readiness approval on Issue #41; **then** authorize a small Phase 5B mock-authority implementation.

