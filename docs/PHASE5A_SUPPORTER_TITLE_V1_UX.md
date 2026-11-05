# Phase 5A — Supporter Title v1: Public Catalog & Receipt UX Specification

Status: **PROPOSAL / STATIC UX AND POLICY MOCK ONLY — NO SALES**

Dependency: Phase 5A readiness [#41](https://github.com/thedusk4203/Minecraft_Server/issues/41), trusted identity [#42](https://github.com/thedusk4203/Minecraft_Server/issues/42), `docs/PHASE5A_IDENTITY_TRUST_DESIGN.md` and `docs/PHASE5A_COMMERCE_STATE_MACHINE.md` (both independent proposals). No login, payment, catalog, UI, entitlements or APIs are built in this PR.

Reviewed 2026-11-05 against official [Minecraft Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines), [EULA](https://www.minecraft.net/en-us/eula) and server policy, with specific attention to noncompetitive entitlements, accessible public pricing *before entering or signing in* and purchaser-accessible online payment history.

## Product decision proposal

**Candidate first SKU:** `supporter_title`, version `1`.

| Property | Proposed behavior / unresolved decision |
| --- | --- |
| Description | Optional supporter identity decoration in permitted text/profile areas |
| Benefit | Cosmetic-only visible label, neutral styling, no gameplay or economy capability |
| Staff impersonation | Prohibit `ADMIN`, `MOD`, official Mojang/Minecraft-looking and misleading badge designs |
| Minecraft cape | Not a cape, cape substitute or cosmetic using an official cape display channel |
| Gameplay permissions | **None**: no `/fly`, speed, queue priority, max claims, auction/market privileges or paid commands |
| PvE | No dungeon access/keys, Stone Warden/Stone Vault buffs, additional COINS or shards |
| Inventory | No item, armor, weapon, block, currency, loot box or random reward |
| Duration | **Not decided** — prefer one simple permanent entitlement for first mock; never imply permanence before approval |
| Price, currency and taxes | **TBD** — operator must publish real price, taxes, currency and conditions before opening sales |
| Refund/chargeback | Verified refund revokes only the supporter cosmetic; never touches game rewards |
| Java/Bedrock parity | Same visible benefit meaning, no unsupported features or discriminatory display |
| Disabled/hidden chat | Alternative visible profile surface or clear accessibility fallback |
| User control | Player may hide the cosmetic without surrendering ownership |
| Cancellation | Not a subscription in v1; subscriptions need separate policy/design review |
| Gift/transfer | Not supported in v1, pending verified linking/ownership design |
| Purchase prerequisite | Verified buyer identity subject from Issue #42; UNKNOWN/ambiguous claims cannot buy |

Product rationale: a low-risk *informational* supporter label is simpler to audit for competitive advantage than a vanity item with collision, hitbox, server-performance or visibility implications. It must not be promoted as a "donation perk": a purchaser-specific badge is a disclosed cosmetic **purchase**.

### Competitive-effect review (proposed acceptance)

| Threat | Expected mitigation | Release blocker |
| --- | --- | --- |
| Chat staff impersonation | Hardcoded disallowed rank prefixes, moderation review | Any ambiguous operator/admin style |
| Chat readability | Readable fallback, no excessive color/animation | Inaccessible or misleading display |
| Advertising amplification | Same anti-spam rules/rate limits | Paid chat bypass |
| Java/Bedrock mismatch | Parity test and platform capability disclosure | Meaningful mismatch of a paid feature |
| Effect on PvP or PvE | No combat/client metadata, damage or interaction changes | Any advantage |
| Refund/revoke race | Render from durable active entitlement version | Revoked title remains active after reconciliation |
| Load/performance | No unbounded visual packet effect | Noticeable gameplay lag from prefix |
| Identity/link collision | Pay eligibility blocked without trusted mapping | Shared Java/Bedrock UUID enables entitlement theft |

## Public no-login catalog mock

This is a **text wireframe** of a hypothetical website; no real page or route is created. The operator must supply a real independently branded server name, public contact, jurisdiction and payment/tax policies before publication. Do not present this template as a live shop.

```text
SERVER NAME (operator name TBD)
Optional cosmetics — Free to play without purchasing

This is an independent community Minecraft server,
not an official Minecraft / Mojang / Microsoft product.

[Home] [Server information] [Cosmetics] [Support] [Purchases*]

Supporter Title — v1 [PROPOSED / NOT FOR SALE]
A cosmetic supporter label for your player profile or chat.
No gameplay bonuses, currency, PvE advantages or privileged commands.
Shown to both Java and Bedrock users (parity must be verified).

Price: NOT SET — sales disabled
Currency/taxes: NOT SET — sales disabled
Duration/expiry: NOT SET — sales disabled
Refund/chargeback terms: NOT SET — sales disabled
Benefit: cosmetic title only; can be hidden
Contact: NOT SET — launch blocked

[Preview only]   [Purchase unavailable]

What you DON'T get:
- gameplay currency or items
- paid dungeons, bonus drops, boosts or market privileges
- queue priority, admin status, capes or account access

*Purchases requires authenticated verified purchaser account.
```

**Publication gate:** the public catalog must be reachable without signing in and must clearly disclose exact price, currency, taxes/fees where applicable, full benefit and duration, refund/support terms, server operator and contact **before** a user joins/signs up/signs in. The copy above deliberately displays placeholders to prevent misleading users during design. Do not route users to payment while placeholders remain.

## Authenticated buyer purchase history mock

Accessible online **without needing to be inside Minecraft** after suitable authenticated portal login, scoped strictly to the actual buyer. This is a target UX contract; authentication method and web implementation are not decided.

```text
Purchases — verified account (subject intentionally redacted)
[Overview] [Receipts] [Support] [Sign out]

Order ID                 DATE        PRODUCT           AMOUNT      STATUS
<sample-generated-id>    sample      supporter_title    SAMPLE      REFUNDED

Selected purchase:
- Product: supporter_title v1 (definition hash and terms version)
- Charged: exact minor-unit amount + currency + disclosed taxes
- Provider: sanitized reference (never provider secret)
- Paid at: verified server timestamp
- Status: Verified / Fulfilled / Refunded / Disputed
- Entitlement: Active / Revoked / Expired
- Fulfillment/Refund: immutable audit reference
- Terms shown at purchase: downloadable retained version
- Download receipt / ask for help / request a refund: links only
  after customer support and legal processes are defined
```

A purchaser must not see other users' orders, payment identities or addresses. The history must remain available even when the game server is offline. Support users require role-based access, audit and necessity-limited visibility. Deleting/revoking a cosmetic must **not** erase required financial history.

## Interaction flow (mock)

```text
PUBLIC, NO LOGIN
  ├─ read independent-server disclaimer + cosmetics description
  ├─ see explicit total price/currency, terms, tax and duration
  └─ choose purchase (disabled until launch approvals)
         |
         v
  VERIFIED WEB SESSION (future)
         |
  authenticated purchaser subject matches approved game binding?
         ├─ no / UNKNOWN / linked-account ambiguity: block purchase, offer support
         └─ yes: create immutable order intent with fixed SKU+price snapshot
                        |
                        v
               provider verification (future, mock-only tests first)
                        |
                        v
          durable entitlement after verified capture (future)
                        |
                        v
          join/rejoin to see supporter title; allow local hide
                        |
                 refund/chargeback
                        |
                        v
           durable revocation -> remove cosmetic view
           maintain buyer's private receipt history
```

Do not accept screenshots, game chat commands or successful browser redirects as evidence that money has been paid. Do not allow a staff command to mint a paid cosmetic without a distinct audited policy and privilege design.

## Accessibility and moderation

- Display title and text color must remain legible for color-blind users and with Minecraft/chat accessibility settings; never depend only on color for meaning.
- Provide text alternatives and no flicker/animated symbols.
- Do not bypass existing mute, report, anti-spam or moderation mechanisms for supporters.
- Allow users to disable the personal cosmetic independently of entitlement ownership and restore it without new payment.
- Player aliases, recent display names and account linked status are not payment authority.
- Make the page usable on desktop/mobile with keyboard, screen reader and plain text; include prominent terms and a straightforward support route.
- Show nonpaying players identical server functionality and game access.

## Mock UX acceptance cases

| ID | Case | Pass criteria |
| --- | --- | --- |
| UX01 | Fresh anonymous visitor | Catalog/disclaimer/benefit/price available **without login** |
| UX02 | Missing price, tax, term, operator or support info | Checkout remains disabled |
| UX03 | SKU definition changes | Versioned new listing; old buyer's saved terms remain |
| UX04 | Read-only preview | No entitlement or payment created |
| UX05 | Valid nonpaying Java player | Same game progression, market/PvE access |
| UX06 | Valid nonpaying Bedrock player | Same progression; no purchase required |
| UX07 | Unverified / UNKNOWN buyer | No checkout and no paid grant |
| UX08 | Linked Bedrock identity ambiguity | No automatic entitlement transfer |
| UX09 | Verified purchaser | Only own purchase history visible |
| UX10 | Another authenticated purchaser | Cannot read prior purchaser's history |
| UX11 | Refund | Cosmetic revoked; receipt remains historically available |
| UX12 | Player hides label | Entitlement stays active; no duplicate on restore |
| UX13 | Server restart | Current versioned entitlement projection restored |
| UX14 | Java/Bedrock visual parity | Equivalent meaning and moderation |
| UX15 | Confusing "admin"/official label | SKU rejected before listing |
| UX16 | Screen-reader/keyboard and low-vision mode | Core terms/history/navigation usable |
| UX17 | Game server offline but website available | Purchaser can still review history/contact |
| UX18 | Payment provider unavailable | Error page does not claim a successful purchase |
| UX19 | No-money fake fixture | UI labels clearly marked mock; no real orders or secrets |
| UX20 | Refund colliding with grant replay | Revoked display cannot be re-enabled |

None of these positive UX cases is claimed as implemented or tested. The first public release must include a buyer-facing history page and real support contact, not merely an internal database.

## Launch blocking decisions (still open)

1. Real server/operator brand, contact channel and country/jurisdiction.
2. Official policy review and legal/consumer/privacy/tax requirements.
3. Concrete SKU title style, permanence/duration, parity and competitive-impact approval.
4. Public *actual* price/tax/currency, refund terms, privacy policy and versioned terms.
5. Verified Java/Bedrock subject authentication, including linked accounts (Issue #42).
6. Secure external purchaser authentication and independent online purchase-history access.
7. Payment provider and signed-event, refund/chargeback, reconciliation and incident-response designs.
8. End-to-end mock purchase/refund tests and subsequent implementation readiness signoff under Issue #41.

**Decision:** This static document is a UX/policy review input. Phase 5A readiness remains blocked, Phase 5B remains unstarted and real-money sales are prohibited until separately approved.
