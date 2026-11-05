# Phase 5A — Provider-Neutral Mock Order, Entitlement and Refund State Machine

Status: **PROPOSED / DESIGN ONLY — NOT APPROVED FOR PHASE 5B IMPLEMENTATION**

Parent: [#41](https://github.com/thedusk4203/Minecraft_Server/issues/41). Identity dependency: [#42](https://github.com/thedusk4203/Minecraft_Server/issues/42). The first cosmetic SKU in this design is a *hypothetical* noncompetitive `supporter_title` v1. No user can buy it, no migration exists and no real payment provider is configured.

Reviewed against `docs/PHASE5A_COMMERCE_DESIGN.md`, `docs/PHASE5A_READINESS_REVIEW.md`, V1 game economy, V7/V8 encounter rewards and V9 Stone Vault progression on 2026-11-05.

## Safety and business invariant

Free-to-join authenticated Java/Bedrock gameplay stays available without any purchase. Paid cosmetics are optional and provide **no** gameplay, command, market, COINS, Stone Warden or Stone Vault advantage. Never sell player capes, paid randomized loot, combat benefits or a conversion between real currency and `COINS`.

Commerce's source of truth must be **new durable commerce authority**, separate from the existing `economy_account`, `economy_transaction`, `encounter_run`, `encounter_reward`, `encounter_reward_item` and `structured_activity_run`. A cosmetic projection is just a derived view, not a source of money, ownership or entitlement state.

A future web/HTTP commerce boundary should run **outside the Paper main thread**; a small Rust backend is a candidate for provider-agnostic mock purchase and receipt services, while Paper Java would only consume authorized entitlement projections. The service, framework, deploy model and inter-service authentication require a separate approved implementation design. No Rust service is created here.

## Trust boundaries

```text
Verified buyer account / purchaser portal
                 |
       mock-only checkout intent
                 |
                 v
    external commerce service (future)
       validate SKU and immutable price
                 |
                 v
        PostgreSQL commerce tables  <--- future provider webhook receiver
                 |                         signature-verified first
         durable entitlement state         durable inbox/deduplication
                 |
                 v
      versioned entitlement projection
                 |
                 v
          Paper cosmetic adapter
        (no grant command authority)
```

A browser redirect, success notification, client `/shop` command, screenshot, game UUID or unsigned webhook never establishes payment. **Verified account binding from Issue #42 is a mandatory prerequisite** for any paid order or grant. Mock CI may use synthetic deterministic verified subjects in fixtures; they do not represent authentic purchases.

## Immutable identifiers and money semantics

- `sku_key` and positive integer `sku_version`; benefit definition hash, public terms version, product description and legal/price disclosures immutable once published.
- `order_id` UUID and a separate idempotent `buyer_intent_key` scoped to the verified buyer/session. Repeating the same intent with identical immutable parameters returns the same order; different payload with same key fails closed.
- `provider` and `payment_id` represent a single verified charge authority, not a webhook event. A charge can have multiple **different** verified events (capture, refund, dispute).
- `provider_event_id` plus `provider` uniquely identifies a delivered notification. A repeated ID with a different canonical payload digest is an **integrity conflict**, not a valid replay.
- `refund_id` uniquely identifies a provider refund/chargeback operation.
- Amounts use **integer minor units** with an explicit currency code, not floating-point money. Currency conversion, partial payments and tax treatment must be explicitly reviewed before release.
- `grant_key` is a stable composite derived from `order_id + benefit_key + benefit_version + verified_target_player_id`; do not use a mutable username.
- `entitlement_id` remains stable across replay, restart, cache rebuild and temporary player disconnection. Refund *revokes* the entitlement; it never deletes history.
- Every terminal mutation has a stable audit identity and correlation ID, without full card, payment-key or sensitive webhook contents in logs.

## Proposed future tables (NO SQL migration approved)

| Authority | Minimum fields and uniqueness | Purpose |
| --- | --- | --- |
| `commerce_sku_version` | `(sku_key, sku_version)` PK; fingerprint, description/terms version, price_minor, currency, active window | Immutable publicly described cosmetic |
| `commerce_order` | `order_id` PK, unique `(buyer_id, buyer_intent_key)`, SKU snapshot, target binding, state, version | Stable purchaser intent and price snapshot |
| `commerce_payment` | Unique `(provider, payment_id)`; order FK, amount/currency, verified timestamps and terminal marker | One charge identity and immutable amount match |
| `commerce_event_inbox` | Unique `(provider, provider_event_id)`, payload digest, verified-at, event type, processed/retry state | Dedup and out-of-order intake |
| `commerce_entitlement` | `entitlement_id` PK, unique grant key, owner binding/player_id, benefit/version, `ACTIVE/REVOKED/EXPIRED`, monotonic version | Durable ownership, never a Bukkit permission |
| `commerce_refund` | Unique `(provider, refund_id)`; payment/order FK, amount/currency, status, applied_at | Verified financial reversal and revocation |
| `commerce_fulfillment` | Unique `(order_id, grant_key)`; state, reconciliation watermark, attempts, last error | Crash-safe eventual cosmetic projection |
| `commerce_audit` | Stable event identity and correlation; bounded/redacted metadata | Explain paid ownership and refund state |

**All of these are candidate entities, not approved names, migrations or commands.** Physical keys, isolation, foreign keys, source event schema, indices and policy retention need explicit PostgreSQL design review. Deletion/retention must meet applicable customer rights and financial record obligations.

### Catalog and order snapshots

At order creation, use one authoritative published SKU version and snapshot `sku_key`, `sku_version`, `benefit_hash`, `price_minor`, `currency`, `terms_version`, and verified buyer/target binding. Later changes to public price must create a new version, not mutate an existing order. An expired, retired or disabled SKU cannot be silently purchased; reprice requires a **new buyer intent** and affirmative consent.

## Proposed order state machine

```text
CREATED -> AWAITING_VERIFICATION -> VERIFIED -> FULFILLING -> FULFILLED
   |               |
   +--> CANCELLED  +--> EXPIRED

VERIFIED / FULFILLING / FULFILLED
       --> REFUND_PENDING --> REFUNDED (terminal financial result)
                                      --> ENTITLEMENT_REVOKED

All states -- verified dispute/chargeback --> REVOKE_REQUIRED
REVOKE_REQUIRED --> REFUNDED or DISPUTED_CLOSED (policy decision)
```

The names are conceptual; actual database representation must avoid duplicated booleans or inconsistent order/entitlement states. **Refund intent can arrive before capture**: keep a verified pending refund/tombstone by the charge identity, reconcile in provider-independent order, and never grant later merely because a delayed `captured` event arrives.

| Current state | Trusted event | Required action | Explicitly forbidden |
| --- | --- | --- | --- |
| CREATED | create intent replay (same fingerprint) | Return same order | New order/price snapshot |
| CREATED | mock checkout initiated | AWAITING_VERIFICATION | Creating entitlement |
| AWAITING_VERIFICATION | verified matching capture | VERIFIED, durable payment receipt | Trusting redirect or mismatched money |
| VERIFIED | fulfillment reconciliation | Commit one entitlement/grant key + audit | More than one grant per key |
| FULFILLING | commit confirmed | FULFILLED; refresh projection idempotently | Assuming projection equals DB commit |
| Any pre-payment | timeout/cancellation | EXPIRED/CANCELLED according to explicit rules | Treating as settled |
| Any relevant state | verified duplicate capture/event | Replay current immutable result | Duplicate grant/ledger debit |
| VERIFIED/FULFILLED | verified full refund | Persist refund and revoke entitlement atomically | Deleting payment history or touching COINS |
| REFUNDED/REVOKED | delayed capture or reconciliation | Return terminal revocation; no regrant | State regression to ACTIVE |
| Any | unverified/conflicting/unknown event | Reject or quarantine + audit | Moving money/entitlement authority |

**v1 decision required**: do not support partial refunds, mixed currencies, multi-benefit bundles, subscription renewals or gifting until there is a reviewed deterministic state machine for each. For now treat them as unfulfilled/quarantined cases rather than applying a guessed fraction of a cosmetic.

## Transaction and idempotency algorithm (design pseudocode)

```text
ingest(raw_event):
  authenticate event using chosen provider's approved verifier
  validate signature/timestamp/request-size before business handling
  normalize event id, charge id, type and bounded payload digest
  begin database transaction
    INSERT inbox(provider, event_id, digest) ON CONFLICT:
      if existing digest != digest -> integrity conflict + auditable quarantine
      if identical + processed -> return recorded outcome
      if identical + pending -> serialize/continue safely
    identify payment/order; lock relevant payment and order rows
    if order unresolved -> durable pending inbox, no entitlement
    validate buyer, SKU version, captured amount/currency and provider charge identity
    apply guarded legal state transition
    on authorized fulfillment:
      UPSERT exactly one entitlement by grant_key
      write audit atomically and schedule derived projection
    on full refund/chargeback:
      write unique refund and durable entitlement revocation
      ensure any later fulfillment sees the terminal revocation
    mark inbox processed with outcome
  commit

project_entitlement(entitlement_id, version):
  read committed current entitlement and current version
  apply ACTIVE/REVOKED cosmetic presentation idempotently
  acknowledge projection watermark
  on crash or offline player: retry from durable authority, never mint a new grant
```

The above is not executable SQL and does not imply an implemented verifier. An actual reviewer must choose isolation/row-lock order, deadlock retry policy, inbox availability semantics and audit integrity. If an event references an unavailable order or an unverified user, **persist the correct quarantine/pending state** without granting anything.

### Exactly-once scope

**Exactly once** refers to PostgreSQL's *committed ownership/grant identity* under concurrency, retries and crash recovery. An external PSP webhook is normally delivered **at least once**, and cosmetic presentation/refresh may be applied more than once provided the operation is idempotent and converges to the committed entitlement state. Do **not** promise exactly-once cross-system delivery when the design can guarantee only stable, repeatable convergence.

## Refund/revocation race invariant

When a verified full refund, chargeback or approved revocation is committed:
- entitlement becomes `REVOKED` with a strictly increasing version;
- no delayed capture, stale fulfillment worker, cache snapshot or player reconnect can re-enable it;
- the cosmetic projection is removed on next successful projection/online refresh;
- historical order/payment/refund receipts remain visible to the authorized purchaser and support roles;
- frozen gameplay COINS, market balances, Stone Warden Shards and dungeon progression remain unchanged;
- any order that is ambiguous due to a pending dispute is held without a new grant until its state is resolved.

**Potentially nonmonotonic financial event timelines** must be modeled separately from the monotonic entitlement-version and refund-tombstone semantics. A full-refund terminal marker must dominate later grant retries for that charge.

## Identity, authorization and privacy

Orders may target only a verified `player_id` tied to a unique trusted subject binding; Issue #42 remains blocking. An existing `player_identity` row with platform UNKNOWN is insufficient. Java and Bedrock linked sessions must not transfer paid ownership automatically. The buyer's authenticated online purchase history must use its own secure login/session design; no access based solely on public UUID or gamertag.

Log sanitized event IDs, state transitions and non-secret correlation data. Never store PAN/CVV/full card details, raw bearer signatures, authentication secrets or credential-filled request bodies in gameplay audit tables. Payment support, tax, invoices, refunds, age/privacy obligations and data retention need local operator review before live processing.

## Mock-only test matrix (future PR acceptance IDs)

| ID | Fixture | Expected invariant |
| --- | --- | --- |
| CM01 | Create same buyer intent twice | One order, identical immutable snapshot |
| CM02 | Same intent key but changed SKU or amount | Conflict; no new order |
| CM03 | Duplicate provider event ID/payload | One inbox event and one result |
| CM04 | Same event ID with different digest | Security conflict/quarantine; no grant |
| CM05 | Correct event ID but wrong amount/currency | No verified payment or entitlement |
| CM06 | Invalid/missing signature, expired timestamp | No event-driven transition |
| CM07 | Two concurrent valid capture workers | Exactly one grant/audit/entitlement |
| CM08 | Capture arrives before order mapping | Pending inbox; later correct reconciliation |
| CM09 | Refund arrives before delayed capture | No post-refund grant |
| CM10 | Full refund after fulfilled | One revocation; projection removed |
| CM11 | Repeated refund and duplicate reversal events | One refund/revocation receipt |
| CM12 | Refund concurrent with fulfillment | Refund/terminal state wins, no regrant |
| CM13 | Crash before DB commit | Retry produces one intended committed result |
| CM14 | Crash after commit, before player projection | Reconciliation applies one cosmetic view |
| CM15 | Player offline or reconnects while projection retries | Committed ownership stable |
| CM16 | Wrong/UNKNOWN Java/Bedrock binding | Fail paid eligibility, free gameplay unaffected |
| CM17 | Linked Bedrock masquerading under Java effective UUID | No unauthorized cross-subject purchase or grant |
| CM18 | Retired SKU or changed terms/pricing | Cannot settle against unapproved replacement |
| CM19 | Cross-server concurrent retry / out-of-order events | Single canonical order, no deadlock/lost grant |
| CM20 | Post-refund stale entitlement snapshot | Cannot restore ACTIVE after REVOKED |
| CM21 | Purchaser receipt/history access vs unrelated player | Only authenticated purchaser/support sees records |
| CM22 | All commerce test cases with game data snapshots | **Zero writes** to V1 COINS ledger, V6–V9 encounters/rewards and market |
| CM23 | Unrecognized partial refund, multi-currency or subscription | Quarantine/fail closed; no guessed compensation |
| CM24 | Replayed admin override lacking approval/audit | Reject; no manual paid grant |

Each mock test must declare its exact input event, expected persisted state/audit counts and replay behavior. Future implementation code must run PostgreSQL integration and existing full Minecraft E2Es on exact head and merged main.

## Open decisions / exit gate

1. Operator explicitly approves whether the first SKU `supporter_title v1` is permanent or duration-bound, its benefit fingerprint, competitive-impact review and real-price/terms disclosure. **No price chosen here.**
2. Technical/security reviewer signs off trusted subject binding and linked accounts (#42), webhook verification adapter, transaction isolation, event replay and refund precedence.
3. Reviewers approve order/entitlement schema, constraints, failure retries, retention policy and the exact synthetic event fixtures above.
4. Operator establishes a public pre-login catalog, independent-brand disclaimer, real purchase support contact, purchaser-accessible online history and applicable consumer/privacy/tax/payment policies *before any real checkout*.
5. Only then scope a **small Phase 5B provider-neutral MOCK authority PR**, with no real gateway credentials and no production cosmetic permissions until separately reviewed.

This design deliberately leaves Phase 5A readiness **BLOCKED** and Phase 5B **NOT STARTED**. No payment account, schema, migration, provider URL, secret or production code is created by this PR.
