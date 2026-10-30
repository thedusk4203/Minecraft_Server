# Game Design Foundation — v0.1

## Design objective
Create a survival experience that remains immediately understandable to vanilla players while providing enough structured progression to sustain medium- and long-term retention.

## Core loop
```text
Explore / gather / build
  -> convert effort into resources and currency
  -> specialize through skills/jobs
  -> trade and cooperate
  -> unlock quests and encounters
  -> defeat harder content
  -> earn status, cosmetics and seasonal progress
  -> repeat with new goals
```

## Progression layers
### Layer 1 — Vanilla survival
- Mining, farming, building, exploration.
- Must remain useful throughout the game.

### Layer 2 — Economy
- Currency earned through multiple activities.
- Player-to-player trade is strategically important.
- Avoid a single dominant money printer.

### Layer 3 — Skills / jobs
Candidate categories:
- Mining
- Farming
- Fishing
- Woodcutting
- Combat
- Exploration

Progression should mostly improve efficiency, access and expression rather than create extreme combat power gaps.

### Layer 4 — Objectives and quests
- Onboarding chain
- Daily objectives
- Weekly objectives
- Region/content quest chains
- Event objectives

### Layer 5 — PvE encounters
- Elite mobs
- Bosses
- One MVP dungeon or equivalent structured encounter

### Layer 6 — Seasonal/meta progression
- Collections
- Titles
- Cosmetics
- Achievement tracks
- Seasonal status

## Economy principles
### Sources
Potential currency sources:
- jobs/skills
- quests
- NPC contracts
- event rewards
- limited item sales

### Sinks
Required sinks should include several of:
- market fees
- repair/services
- travel/teleport fees where appropriate
- cosmetic crafting
- upgrade fees
- land convenience fees that do not punish casual players
- event entry costs for optional high-value activities

### Rules
- Every major source must have at least one countervailing sink.
- Currency creation and destruction must be measurable.
- Player trading must not be invalidated by unlimited NPC buy prices.
- New-player income must be sufficient to teach the economy without causing inflation.
- Balance changes require telemetry and a rollback path.

## PvP philosophy
Default recommendation for MVP:
- Survival progression should not require open-world PvP.
- PvP should be opt-in, zoned, event-based or otherwise explicit.
- Monetized benefits must not determine competitive PvP outcomes.

## Land and grief protection
A player should understand protection during onboarding.

Minimum requirements:
- clear ownership boundaries
- trusted-member access
- anti-grief defaults
- reasonable expansion path
- recovery/audit capability for staff

## Boss design — MVP template
The first boss should validate the encounter framework, not maximize complexity.

Required:
- clear telegraphs
- at least two mechanics beyond basic melee
- support for multiple players
- anti-cheese boundaries
- recoverable failure state
- deterministic reward rules
- telemetry for attempts, deaths, duration and completion

Example structure:
```text
Phase 1: readable attacks + adds
Phase 2: area-control mechanic
Phase 3: pressure/enrage mechanic
```

## Dungeon design — MVP template
Target duration: short enough to replay and test frequently.

Required:
- entry rule
- party-size assumptions
- 2–3 encounter beats
- one boss
- clear reset behavior
- deterministic anti-duplication reward handling
- disconnect/reconnect behavior defined

## Content authoring
Content should be data-driven where practical.

Example conceptual schema:
```yaml
quest:
  id: onboarding_first_trade
  prerequisites: [onboarding_land]
  objectives:
    - type: earn_currency
      amount: 100
    - type: trade_once
  rewards:
    xp: 150
    currency: 50
```

AI may generate candidate content at scale, but generated content must pass:
- schema validation
- economy validation
- duplication checks
- progression checks
- human review for flagship content

## Anti-grind rule
Progression must not rely only on repeating the same profitable action indefinitely.
Use diminishing incentives, rotating objectives, unlocks and multiple viable activities to encourage variety.

## Retention hypotheses to test
1. Clear first-hour objectives improve D1 retention.
2. Player economy interaction improves D7 retention.
3. A visible medium-term progression path reduces early churn.
4. Recurring events improve return frequency.
5. Cosmetics/status can monetize without harming competitive fairness.

These are hypotheses, not truths. Instrument them and test them.
