# Product Specification — v0.1

## Product thesis
Build a Vietnamese-first, Java + Bedrock crossplay Minecraft Survival SMP with lightweight RPG progression, a healthy player economy, recurring live content, and non-pay-to-win monetization.

The first product is intentionally narrow. We are validating retention and willingness to pay before expanding into additional game modes.

## Target audience
Primary:
- Vietnamese Minecraft players who want a persistent survival world with progression beyond vanilla.
- Players who prefer low-friction joining: no required modpack, Java and Bedrock supported.
- Social players interested in land, trading, guild-like cooperation, quests, events, bosses and seasonal progression.

Secondary:
- Returning Minecraft players who want structured goals without learning a heavily modded pack.
- Small friend groups who want a stable long-lived server.

## Core promise
Within 10 minutes, a new player should understand what to do next.
Within 60 minutes, the player should have made visible progression and interacted with at least one server-specific system.
Within 7 days, the player should have medium-term goals involving economy, skills, quests, social play or progression.
Within 30 days, the player should still have aspirational goals through seasons, prestige, difficult encounters, collections, cosmetics and community competition.

## Product pillars
1. **Survival first** — vanilla Minecraft remains legible and familiar.
2. **Meaningful progression** — skills, quests, economy and encounters add goals without replacing survival.
3. **Social economy** — players create value for each other through trade, markets and specialization.
4. **Crossplay** — Java and Bedrock are first-class clients wherever technically practical.
5. **Live world** — events, rotating objectives and seasonal content keep the world active.
6. **Fair monetization** — revenue must not depend on selling overwhelming competitive power.
7. **AI-native operations** — content, testing, analytics and administration should be automatable while keeping humans in control of consequential decisions.

## MVP v0.1 scope
### Required
- Java + Bedrock crossplay
- New-player onboarding
- Persistent survival world
- Land claiming / protection
- Economy and currency
- Player trading / market
- Jobs or skills progression
- Basic quest system
- Daily and weekly objectives
- One dungeon or equivalent instanced/controlled PvE encounter
- One boss encounter
- Cosmetic entitlement framework
- Rank / supporter entitlement framework
- Discord integration baseline
- Analytics baseline
- Moderation and administration baseline
- Backup / restore process
- Staging and production deployment model

### Explicitly out of scope for MVP
- Skyblock
- Prison
- BedWars
- Factions as a separate mode
- Large modpack dependency
- Dozens of dungeons
- Hundreds of handcrafted quests
- Multiple production shards before load requires them
- Complex player-owned shops requiring custom client UI
- Real-money loot boxes

## Core player loop
Explore / gather / build
→ earn resources and currency
→ trade or specialize
→ improve skills and gear
→ unlock quests and encounters
→ defeat bosses / complete objectives
→ gain status, cosmetics and seasonal progress
→ return for social play, events and new goals

## New-player journey
### First 10 minutes
- Join successfully from Java or Bedrock.
- Understand server rules and primary objective without reading a wall of text.
- Reach the survival world.
- Receive one clear starter objective.
- Learn how land protection works.
- See one server-specific progression mechanic.

### First hour
- Complete an onboarding quest chain.
- Earn first meaningful amount of currency.
- Gain first skill/job level.
- Buy, sell or trade at least once.
- Establish or join a protected home area.

### First 7 days
- Select preferred progression paths.
- Participate in a daily/weekly objective or event.
- Interact with another player economically or socially.
- Attempt the first dungeon/boss content.
- Understand longer-term progression.

## Monetization principles
Allowed directions:
- Cosmetics
- Titles, badges, chat presentation and profile customization
- Cosmetic pets/effects where they do not provide competitive power
- Supporter ranks with carefully reviewed quality-of-life benefits
- Seasonal cosmetic progression
- Community-wide goals or events

Forbidden by product policy unless explicitly re-reviewed:
- Direct sale of dominant weapons/armor
- Large combat/stat multipliers
- Paid advantages that meaningfully determine PvP or leaderboard outcomes
- Real-money gambling or loot-box mechanics
- Progression skips that invalidate the core game

Every monetization feature must also be checked against the current Minecraft Usage Guidelines before release.

## Success metrics
### Pre-alpha
- Automated deployment reproducible from repository documentation.
- Java and Bedrock join paths verified.
- Core systems survive restart without data corruption.

### Closed alpha
- At least 20 invited testers complete onboarding.
- >= 80% onboarding completion among testers who remain connected for 10+ minutes.
- No critical duplication or currency exploits left open.
- No P0/P1 data-loss bugs.

### Public MVP target
Initial business validation target, not a guarantee:
- 20–30 stable concurrent players as the first CCU milestone.
- D1 and D7 retention tracked from day one.
- Payer conversion and ARPPU measurable but not optimized at the expense of fairness.
- Server can operate for seven consecutive days without manual emergency intervention.

## Decision rules
When choosing between more features and better retention, choose retention.
When choosing between more game modes and higher player density, choose player density.
When choosing between monetization and trust, choose trust.
When choosing between custom code and an established dependency, prefer the option with lower total operational risk unless custom ownership creates a clear strategic advantage.

## Open product decisions
These should be resolved through experiments or explicit owner decisions, not silently assumed by agents:
- Final server name and brand identity
- Exact art direction of spawn/world
- Whether jobs and skills are one system or two
- Whether the first dungeon is instanced or overworld-controlled
- Season duration
- Exact supporter rank benefits and pricing
- PvP rules outside dedicated events/zones
