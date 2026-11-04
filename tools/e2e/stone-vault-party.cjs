'use strict';

const { execFileSync } = require('node:child_process');
const mineflayer = require('mineflayer');
const { pathfinder, Movements, goals: { GoalNear } } = require('mineflayer-pathfinder');

const LEADER_NAME = 'StoneVaultLead';
const MATE_NAME = 'StoneVaultMate';
const BOT_HOST = process.env.E2E_BOT_HOST || '127.0.0.1';
const BOT_PORT = Number(process.env.E2E_BOT_PORT || '25568');
const BOT_VERSION = process.env.E2E_BOT_VERSION || '26.1';
const COMPOSE_FILE = process.env.E2E_COMPOSE_FILE || 'compose.e2e.yaml';
const PAPER_SERVICE = process.env.E2E_PAPER_SERVICE || 'paper-e2e';
const MELEE_DISTANCE = 2.75;
const HURT_CONFIRM_TIMEOUT_MS = 1_800;
const ARENA_RADIUS = 18.0;
const ARENA_SAFE_RADIUS = 16.0;
const ARENA_SAFE_VERTICAL = 15.0;
const PARTY_CONVERGENCE_DISTANCE = 10.0;
const PARTY_CONVERGENCE_VERTICAL_DELTA = 2.0;
const PARTY_PATHFIND_GOAL_RADIUS = 2;
const CONNECTION_THROTTLE_CLEAR_MS = 5_500;

const vaultDiagnostics = {
  phase: 'init',
  wave: null,
  currentTargetId: null,
  currentAttacker: null,
  lastAttackContext: null,
  lastAttackConfirmed: null,
  lastAttackFailureReason: null,
  targetSeenByAttacker: null,
  targetSeenByObserver: null,
  lastTargetPosition: null,
  lastAttackerToTargetDistance3d: null,
  lastMeleeApproachResult: null,
  confirmedHitsByPlayer: {
    [LEADER_NAME]: 0,
    [MATE_NAME]: 0
  },
  confirmedBossHits: 0
};

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function rcon(command) {
  return execFileSync(
    'docker',
    ['compose', '-f', COMPOSE_FILE, 'exec', '-T', PAPER_SERVICE, 'rcon-cli', command],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }
  ).trim();
}

function safeRcon(command) {
  try {
    return rcon(command);
  } catch (error) {
    return `<rcon failed: ${error.message || error}>`;
  }
}

function positionSnapshot(position) {
  if (!position) {
    return null;
  }
  return {
    x: Number(position.x.toFixed(2)),
    y: Number(position.y.toFixed(2)),
    z: Number(position.z.toFixed(2))
  };
}

function trackedSnapshot(tracked, arenaCenter) {
  if (!tracked) {
    return null;
  }
  const position = tracked.bot.entity?.position || null;
  const shardCount = tracked.bot.inventory
    ? tracked.bot.inventory.items()
      .filter((item) => item.name === 'amethyst_shard')
      .reduce((total, item) => total + item.count, 0)
    : 0;
  const visibleWardens = Object.values(tracked.bot.entities || {})
    .filter((entity) => isIronGolem(entity) && entity.position)
    .map((entity) => ({
      id: entity.id,
      position: positionSnapshot(entity.position),
      distance3d: position ? Number(position.distanceTo(entity.position).toFixed(2)) : null
    }));

  return {
    username: tracked.username,
    connected: Boolean(tracked.bot.entity),
    kicked: tracked.kicked(),
    position: positionSnapshot(position),
    arenaHorizontalDistance: position && arenaCenter
      ? Number(horizontalDistance(position, arenaCenter).toFixed(2))
      : null,
    arena3dDistance: position && arenaCenter
      ? Number(position.distanceTo(arenaCenter).toFixed(2))
      : null,
    insideSafeArena: position && arenaCenter ? insideSafeArena(position, arenaCenter) : null,
    insideRuntimeArena: position && arenaCenter
      ? horizontalDistance(position, arenaCenter) <= ARENA_RADIUS
      : null,
    shardCount,
    visibleWardens
  };
}

function writeFailureDiagnostics(error, leader, mate, arenaCenter) {
  let partyDistanceValue = null;
  let partyVerticalDeltaValue = null;
  if (leader?.bot.entity?.position && mate?.bot.entity?.position) {
    partyDistanceValue = Number(
      partyDistance(leader.bot.entity.position, mate.bot.entity.position).toFixed(2)
    );
    partyVerticalDeltaValue = Number(
      Math.abs(leader.bot.entity.position.y - mate.bot.entity.position.y).toFixed(2)
    );
  }

  const snapshot = {
    phase: vaultDiagnostics.phase,
    wave: vaultDiagnostics.wave,
    error: error?.message || String(error),
    arenaCenter: positionSnapshot(arenaCenter),
    partyDistance: partyDistanceValue,
    partyVerticalDelta: partyVerticalDeltaValue,
    currentTargetId: vaultDiagnostics.currentTargetId,
    currentAttacker: vaultDiagnostics.currentAttacker,
    lastAttackContext: vaultDiagnostics.lastAttackContext,
    lastAttackConfirmed: vaultDiagnostics.lastAttackConfirmed,
    lastAttackFailureReason: vaultDiagnostics.lastAttackFailureReason,
    targetSeenByAttacker: vaultDiagnostics.targetSeenByAttacker,
    targetSeenByObserver: vaultDiagnostics.targetSeenByObserver,
    lastTargetPosition: vaultDiagnostics.lastTargetPosition,
    lastAttackerToTargetDistance3d: vaultDiagnostics.lastAttackerToTargetDistance3d,
    lastMeleeApproachResult: vaultDiagnostics.lastMeleeApproachResult,
    confirmedHitsByPlayer: vaultDiagnostics.confirmedHitsByPlayer,
    confirmedBossHits: vaultDiagnostics.confirmedBossHits,
    leader: trackedSnapshot(leader, arenaCenter),
    mate: trackedSnapshot(mate, arenaCenter),
    bossStatus: safeRcon('boss status'),
    dungeonStatus: safeRcon('dungeon status')
  };
  console.error(`STONE_VAULT_DIAGNOSTICS ${JSON.stringify(snapshot)}`);
}

function installClientTickEndCompatibility(bot) {
  const originalWrite = bot._client.write.bind(bot._client);
  const movementPackets = new Set(['position', 'position_look', 'look', 'flying']);
  bot._client.write = (name, packet) => {
    const result = originalWrite(name, packet);
    if (movementPackets.has(name)) {
      originalWrite('tick_end', {});
    }
    return result;
  };
}

async function waitFor(description, predicate, timeoutMs = 20_000, intervalMs = 100) {
  const deadline = Date.now() + timeoutMs;
  let lastError = null;
  while (Date.now() < deadline) {
    try {
      const value = predicate();
      if (value) {
        return value;
      }
    } catch (error) {
      lastError = error;
    }
    await sleep(intervalMs);
  }
  const suffix = lastError ? `; last error: ${lastError.stack || lastError}` : '';
  throw new Error(`Timed out waiting for ${description}${suffix}`);
}

function horizontalDistance(first, second) {
  const dx = first.x - second.x;
  const dz = first.z - second.z;
  return Math.sqrt(dx * dx + dz * dz);
}

function partyDistance(first, second) {
  return first.distanceTo(second);
}

function partyCombatReady(first, second) {
  return partyDistance(first, second) <= PARTY_CONVERGENCE_DISTANCE
    && Math.abs(first.y - second.y) <= PARTY_CONVERGENCE_VERTICAL_DELTA;
}

function normalizedEntityName(entity) {
  return String(entity?.name || entity?.displayName || '').toLowerCase().replaceAll('_', '');
}

function isZombie(entity) {
  const name = normalizedEntityName(entity);
  return name === 'zombie' || name.includes('zombie');
}

function isSkeleton(entity) {
  const name = normalizedEntityName(entity);
  return name === 'skeleton' || name.includes('skeleton');
}

function isIronGolem(entity) {
  const name = normalizedEntityName(entity);
  return name === 'irongolem' || name.includes('irongolem');
}

function stoneWardenShardCount(tracked) {
  return tracked.bot.inventory.items()
    .filter((item) => item.name === 'amethyst_shard')
    .reduce((count, item) => count + item.count, 0);
}

function hasStoneWardenShard(tracked) {
  return stoneWardenShardCount(tracked) > 0;
}

function createTrackedBot(username) {
  const bot = mineflayer.createBot({
    host: BOT_HOST,
    port: BOT_PORT,
    version: BOT_VERSION,
    username,
    auth: 'offline',
    hideErrors: false
  });
  bot.loadPlugin(pathfinder);
  installClientTickEndCompatibility(bot);

  let kickedReason = null;
  bot.on('kicked', (reason) => {
    kickedReason = reason;
    console.error(`${username} kicked:`, reason);
  });
  bot.on('error', (error) => {
    console.error(`${username} error:`, error);
  });

  return {
    username,
    bot,
    kicked: () => kickedReason
  };
}

async function awaitSpawn(tracked) {
  const { bot, username } = tracked;
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error(`${username} did not spawn within 30 seconds`)), 30_000);
    bot.once('spawn', () => {
      clearTimeout(timeout);
      resolve();
    });
    bot.once('end', (reason) => {
      clearTimeout(timeout);
      reject(new Error(`${username} connection ended before spawn: ${reason}`));
    });
  });
}

function assertConnected(tracked, context) {
  const { bot, kicked, username } = tracked;
  if (kicked()) {
    throw new Error(`${username} was kicked ${context}: ${JSON.stringify(kicked())}`);
  }
  if (!bot.entity) {
    throw new Error(`${username} entity disappeared ${context}`);
  }
}

async function walkToward(tracked, targetPosition, stopDistance, timeoutMs) {
  const { bot } = tracked;
  const deadline = Date.now() + timeoutMs;
  let bestDistance = Number.POSITIVE_INFINITY;
  let lastProgressAt = Date.now();
  bot.setControlState('sprint', true);
  try {
    while (Date.now() < deadline) {
      assertConnected(tracked, 'while moving client-side');
      const target = typeof targetPosition === 'function' ? targetPosition() : targetPosition;
      if (!target) {
        return false;
      }
      const distance = horizontalDistance(bot.entity.position, target);
      if (distance <= stopDistance) {
        return true;
      }
      if (distance + 0.15 < bestDistance) {
        bestDistance = distance;
        lastProgressAt = Date.now();
      }
      await bot.lookAt(target.offset(0, 0.8, 0), true);
      bot.setControlState('forward', true);
      const climbingTowardTarget = target.y - bot.entity.position.y > 0.8;
      const movementStalled = Date.now() - lastProgressAt > 650;
      bot.setControlState('jump', bot.entity.onGround && (climbingTowardTarget || movementStalled));
      await sleep(125);
    }
    return false;
  } finally {
    bot.setControlState('forward', false);
    bot.setControlState('jump', false);
    bot.setControlState('sprint', false);
  }
}

function insideSafeArena(position, arenaCenter) {
  return horizontalDistance(position, arenaCenter) <= ARENA_SAFE_RADIUS
    && Math.abs(position.y - arenaCenter.y) <= ARENA_SAFE_VERTICAL;
}

async function walkIntoEntityMeleeRange(tracked, observer, entityId, arenaCenter, timeoutMs) {
  const { bot } = tracked;
  const deadline = Date.now() + timeoutMs;
  let bestDistance = Number.POSITIVE_INFINITY;
  let lastProgressAt = Date.now();
  bot.setControlState('sprint', true);

  try {
    while (Date.now() < deadline) {
      assertConnected(tracked, 'while approaching Stone Vault combat target');

      if (!insideSafeArena(bot.entity.position, arenaCenter)) {
        await bot.lookAt(arenaCenter.offset(0, 0.8, 0), true);
        bot.setControlState('forward', true);
        const climbingTowardCenter = arenaCenter.y - bot.entity.position.y > 0.8;
        bot.setControlState('jump', bot.entity.onGround && climbingTowardCenter);
        await sleep(125);
        continue;
      }

      const attackerTarget = bot.entities[entityId];
      const observerTarget = observer.bot.entities[entityId];
      vaultDiagnostics.targetSeenByAttacker = Boolean(attackerTarget?.position);
      vaultDiagnostics.targetSeenByObserver = Boolean(observerTarget?.position);
      const target = attackerTarget || observerTarget;
      if (!target?.position) {
        vaultDiagnostics.lastAttackFailureReason = 'target-not-visible';
        vaultDiagnostics.lastMeleeApproachResult = false;
        return false;
      }

      const distance = bot.entity.position.distanceTo(target.position);
      vaultDiagnostics.lastTargetPosition = positionSnapshot(target.position);
      vaultDiagnostics.lastAttackerToTargetDistance3d = Number(distance.toFixed(2));
      if (distance <= MELEE_DISTANCE && attackerTarget) {
        vaultDiagnostics.lastMeleeApproachResult = true;
        return true;
      }
      if (distance + 0.1 < bestDistance) {
        bestDistance = distance;
        lastProgressAt = Date.now();
      }

      await bot.lookAt(target.position.offset(0, 0.9, 0), true);
      bot.setControlState('forward', true);
      const climbingTowardTarget = target.position.y - bot.entity.position.y > 0.8;
      const movementStalled = Date.now() - lastProgressAt > 650;
      bot.setControlState('jump', bot.entity.onGround && (climbingTowardTarget || movementStalled));
      await sleep(100);
    }
    vaultDiagnostics.lastMeleeApproachResult = false;
    vaultDiagnostics.lastAttackFailureReason = vaultDiagnostics.targetSeenByAttacker
      ? 'melee-approach-timeout'
      : 'target-not-visible-to-attacker';
    return false;
  } finally {
    bot.setControlState('forward', false);
    bot.setControlState('jump', false);
    bot.setControlState('sprint', false);
  }
}

async function attackEntityAndConfirmDamage(tracked, observer, entityId, arenaCenter, context) {
  assertConnected(tracked, context);
  const { bot } = tracked;
  vaultDiagnostics.currentTargetId = entityId;
  vaultDiagnostics.currentAttacker = tracked.username;
  vaultDiagnostics.lastAttackContext = context;
  vaultDiagnostics.lastAttackConfirmed = null;
  vaultDiagnostics.lastAttackFailureReason = null;
  vaultDiagnostics.targetSeenByAttacker = Boolean(bot.entities[entityId]?.position);
  vaultDiagnostics.targetSeenByObserver = Boolean(observer.bot.entities[entityId]?.position);
  vaultDiagnostics.lastTargetPosition = null;
  vaultDiagnostics.lastAttackerToTargetDistance3d = null;
  vaultDiagnostics.lastMeleeApproachResult = null;

  const inReach = await walkIntoEntityMeleeRange(tracked, observer, entityId, arenaCenter, 3_500);
  const target = bot.entities[entityId];
  if (!inReach) {
    vaultDiagnostics.lastAttackConfirmed = false;
    if (!vaultDiagnostics.lastAttackFailureReason) {
      vaultDiagnostics.lastAttackFailureReason = 'melee-approach-failed';
    }
    return false;
  }
  if (!target) {
    vaultDiagnostics.lastAttackConfirmed = false;
    vaultDiagnostics.lastAttackFailureReason = 'target-not-visible-to-attacker';
    return false;
  }

  if (!insideSafeArena(bot.entity.position, arenaCenter)) {
    vaultDiagnostics.lastAttackConfirmed = false;
    vaultDiagnostics.lastAttackFailureReason = 'attacker-outside-safe-arena';
    return false;
  }
  if (horizontalDistance(bot.entity.position, arenaCenter) > ARENA_RADIUS) {
    throw new Error(`${tracked.username} left the Stone Vault runtime arena ${context}`);
  }
  const attackDistance = bot.entity.position.distanceTo(target.position);
  vaultDiagnostics.lastTargetPosition = positionSnapshot(target.position);
  vaultDiagnostics.lastAttackerToTargetDistance3d = Number(attackDistance.toFixed(2));
  if (attackDistance > MELEE_DISTANCE) {
    vaultDiagnostics.lastAttackConfirmed = false;
    vaultDiagnostics.lastAttackFailureReason = 'target-out-of-melee-range';
    return false;
  }

  await bot.lookAt(target.position.offset(0, Math.min(1.0, target.height || 1.0), 0), true);
  const attackerId = bot.entity.id;
  const confirmedDamage = new Promise((resolve) => {
    let settled = false;
    let timeout = null;
    const finish = (value) => {
      if (settled) {
        return;
      }
      settled = true;
      if (timeout) {
        clearTimeout(timeout);
      }
      bot.removeListener('entityHurt', onEntityHurt);
      bot.removeListener('entityGone', onEntityGone);
      resolve(value);
    };
    const onEntityHurt = (entity, source) => {
      if (!entity || entity.id !== entityId) {
        return;
      }
      if (source && source.id !== attackerId) {
        return;
      }
      finish(true);
    };
    const onEntityGone = (entity) => {
      if (entity?.id === entityId) {
        finish(true);
      }
    };
    bot.on('entityHurt', onEntityHurt);
    bot.on('entityGone', onEntityGone);
    timeout = setTimeout(() => finish(false), HURT_CONFIRM_TIMEOUT_MS);
  });

  vaultDiagnostics.lastAttackFailureReason = 'damage-not-confirmed';
  bot.attack(target, true);
  const confirmed = await confirmedDamage;
  vaultDiagnostics.lastAttackConfirmed = confirmed;
  vaultDiagnostics.lastAttackFailureReason = confirmed ? null : 'damage-not-confirmed';
  return confirmed;
}

async function pathfindToward(tracked, targetTracked, timeoutMs) {
  const { bot, username } = tracked;
  assertConnected(tracked, 'before pathfinding convergence');
  assertConnected(targetTracked, 'before pathfinding convergence target');

  const target = targetTracked.bot.entity.position.clone();
  const movements = new Movements(bot);
  movements.canDig = false;
  movements.allow1by1towers = false;
  bot.pathfinder.setMovements(movements);

  const goal = new GoalNear(
    Math.floor(target.x),
    Math.floor(target.y),
    Math.floor(target.z),
    PARTY_PATHFIND_GOAL_RADIUS
  );

  try {
    await Promise.race([
      bot.pathfinder.goto(goal),
      sleep(timeoutMs).then(() => {
        throw new Error(`${username} pathfinding convergence timed out after ${timeoutMs}ms`);
      })
    ]);
  } catch (error) {
    console.log(`${username} pathfinding convergence fallback: ${error.message || error}`);
  } finally {
    bot.pathfinder.stop();
  }

  assertConnected(tracked, 'after pathfinding convergence');
  assertConnected(targetTracked, 'after pathfinding convergence target');
  return partyCombatReady(bot.entity.position, targetTracked.bot.entity.position);
}

async function convergePartyClients(leader, mate) {
  assertConnected(leader, 'before Stone Vault convergence');
  assertConnected(mate, 'before Stone Vault convergence');

  if (partyCombatReady(leader.bot.entity.position, mate.bot.entity.position)) {
    return true;
  }

  const leaderHigher = leader.bot.entity.position.y >= mate.bot.entity.position.y;
  const attempts = leaderHigher
    ? [[leader, mate], [mate, leader]]
    : [[mate, leader], [leader, mate]];

  for (const [mover, target] of attempts) {
    const before = partyDistance(leader.bot.entity.position, mate.bot.entity.position);
    const verticalDelta = Math.abs(leader.bot.entity.position.y - mate.bot.entity.position.y);
    console.log(
      `Stone Vault convergence mover=${mover.username} target=${target.username} `
      + `distance=${before.toFixed(2)} verticalDelta=${verticalDelta.toFixed(2)}`
    );

    if (await pathfindToward(mover, target, 20_000)) {
      return true;
    }

    await walkToward(
      mover,
      () => target.bot.entity?.position,
      PARTY_PATHFIND_GOAL_RADIUS,
      7_500
    );
    if (partyCombatReady(leader.bot.entity.position, mate.bot.entity.position)) {
      return true;
    }
  }

  return partyCombatReady(leader.bot.entity.position, mate.bot.entity.position);
}

function nearbyNewEntities(tracked, seenIds, predicate, radius = 36) {
  return Object.values(tracked.bot.entities).filter((entity) => {
    return entity
      && !seenIds.has(String(entity.id))
      && predicate(entity)
      && entity.position
      && entity.position.distanceTo(tracked.bot.entity.position) <= radius;
  });
}

async function waitForWave(tracked, seenIds, expectedCount, predicate, description) {
  const entities = await waitFor(description, () => {
    const found = nearbyNewEntities(tracked, seenIds, predicate);
    return found.length >= expectedCount ? found.slice(0, expectedCount) : null;
  }, 25_000, 100);
  for (const entity of entities) {
    seenIds.add(String(entity.id));
  }
  return entities.map((entity) => entity.id);
}

async function killEntityIds(attackers, observer, entityIds, description, arenaCenter) {
  vaultDiagnostics.wave = description;
  for (let index = 0; index < entityIds.length; index++) {
    const entityId = entityIds[index];
    const deadline = Date.now() + 18_000;
    let confirmedHits = 0;
    let attackAttempt = 0;
    let lastAttacker = attackers[index % attackers.length];
    while (Date.now() < deadline) {
      for (const tracked of attackers) {
        assertConnected(tracked, `while clearing ${description}`);
      }
      const attacker = attackers[(index + attackAttempt++) % attackers.length];
      lastAttacker = attacker;
      const target = observer.bot.entities[entityId] || attacker.bot.entities[entityId];
      if (!target) {
        break;
      }

      const confirmed = await attackEntityAndConfirmDamage(
        attacker,
        observer,
        entityId,
        arenaCenter,
        `while clearing ${description}`
      );
      if (confirmed) {
        confirmedHits++;
        vaultDiagnostics.confirmedHitsByPlayer[attacker.username] =
          (vaultDiagnostics.confirmedHitsByPlayer[attacker.username] || 0) + 1;
        console.log(`${attacker.username} server-confirmed ${description} hit entity=${entityId} count=${confirmedHits}`);
        await sleep(450);
      } else {
        console.log(
          `${attacker.username} could not confirm ${description} hit entity=${entityId}; `
          + `reason=${vaultDiagnostics.lastAttackFailureReason || 'unknown'}; rotating attacker`
        );
        await sleep(150);
      }
    }
    const stillVisible = attackers.some((tracked) => tracked.bot.entities[entityId])
      || Boolean(observer.bot.entities[entityId]);
    if (stillVisible) {
      throw new Error(
        `Could not clear ${description} entity id=${entityId}; confirmedHits=${confirmedHits}; `
        + `lastAttacker=${lastAttacker.username}; `
        + `attackerArenaDistance=${horizontalDistance(lastAttacker.bot.entity.position, arenaCenter).toFixed(2)}`
      );
    }
  }
}

async function main() {
  const leader = createTrackedBot(LEADER_NAME);
  let mate = null;
  let arenaCenter = null;

  try {
    vaultDiagnostics.phase = 'leader-spawn';
    await awaitSpawn(leader);
    await sleep(CONNECTION_THROTTLE_CLEAR_MS);

    vaultDiagnostics.phase = 'mate-spawn';
    mate = createTrackedBot(MATE_NAME);
    await awaitSpawn(mate);

    vaultDiagnostics.phase = 'party-convergence';
    const converged = await convergePartyClients(leader, mate);
    const convergenceDistance = partyDistance(leader.bot.entity.position, mate.bot.entity.position);
    const convergenceVerticalDelta = Math.abs(
      leader.bot.entity.position.y - mate.bot.entity.position.y
    );
    if (!converged) {
      throw new Error(
        `Stone Vault party could not converge to combat-ready start; `
        + `distance=${convergenceDistance.toFixed(2)} `
        + `verticalDelta=${convergenceVerticalDelta.toFixed(2)}`
      );
    }
    console.log(
      `Stone Vault party converged; distance=${convergenceDistance.toFixed(2)} `
      + `verticalDelta=${convergenceVerticalDelta.toFixed(2)} blocks.`
    );

    arenaCenter = leader.bot.entity.position.clone();
    const seenIds = new Set(Object.keys(leader.bot.entities));
    for (const name of [LEADER_NAME, MATE_NAME]) {
      rcon(`effect give ${name} minecraft:resistance 180 4 true`);
      rcon(`effect give ${name} minecraft:regeneration 180 4 true`);
      rcon(`effect give ${name} minecraft:strength 180 20 true`);
    }

    vaultDiagnostics.phase = 'activity-start';
    leader.bot.chat(`/dungeon start ${MATE_NAME}`);

    await waitFor('Stone Vault runtime start', () => {
      const status = rcon('dungeon status');
      return status.includes('Active Stone Vault activities: 1') && status.includes('beat=OUTER_SEAL');
    }, 25_000, 250);

    vaultDiagnostics.phase = 'outer-seal-observe';
    const outerIds = await waitForWave(
      leader,
      seenIds,
      4,
      isZombie,
      'four Outer Seal sentinels'
    );
    console.log(`Outer Seal observed: ${outerIds.join(',')}`);

    vaultDiagnostics.phase = 'outer-seal-combat';
    await killEntityIds([leader, mate], leader, outerIds, 'Outer Seal', arenaCenter);

    vaultDiagnostics.phase = 'inner-guard-transition';
    await waitFor('Inner Guard durable/runtime transition', () => {
      const status = rcon('dungeon status');
      return status.includes('beat=INNER_GUARD');
    }, 20_000, 250);

    vaultDiagnostics.phase = 'inner-guard-observe';
    const innerIds = await waitForWave(
      leader,
      seenIds,
      4,
      (entity) => isZombie(entity) || isSkeleton(entity),
      'four Inner Guard mobs'
    );
    console.log(`Inner Guard observed: ${innerIds.join(',')}`);

    vaultDiagnostics.phase = 'inner-guard-combat';
    await killEntityIds([mate, leader], leader, innerIds, 'Inner Guard', arenaCenter);

    vaultDiagnostics.phase = 'boss-observe';
    const boss = await waitFor('Stone Warden after Stone Vault waves', () => {
      return nearbyNewEntities(leader, seenIds, isIronGolem).at(0) || null;
    }, 25_000, 100);
    seenIds.add(String(boss.id));
    console.log(`Stone Vault Stone Warden observed: id=${boss.id}`);

    vaultDiagnostics.phase = 'boss-activation';
    await waitFor('Stone Vault boss activation status', () => {
      const status = rcon('dungeon status');
      return status.includes('beat=BOSS') && status.includes('bossActive=true');
    }, 15_000, 250);

    vaultDiagnostics.phase = 'boss-combat';
    const killDeadline = Date.now() + 35_000;
    let attackIndex = 0;
    let confirmedBossHits = 0;
    while (Date.now() < killDeadline) {
      for (const tracked of [leader, mate]) {
        assertConnected(tracked, 'during Stone Vault boss combat');
      }
      const status = rcon('boss status');
      if (status.includes('No active Stone Warden encounter.')) {
        vaultDiagnostics.phase = 'reward-wait';
        await waitFor(
          'Stone Warden Shard in both Stone Vault client inventories',
          () => hasStoneWardenShard(leader) && hasStoneWardenShard(mate),
          20_000,
          250
        );

        // Replay each player's normal recovery surface AFTER successful delivery.
        // No RCON reward mutations or inventory injection: only real player commands.
        const initialCounts = [stoneWardenShardCount(leader), stoneWardenShardCount(mate)];
        if (initialCounts[0] !== 1 || initialCounts[1] !== 1) {
          throw new Error(`Stone Vault delivered duplicate/missing shards: ${initialCounts.join(',')}`);
        }
        leader.bot.chat('/boss recover');
        mate.bot.chat('/boss recover');
        await sleep(2_000);
        const replayCounts = [stoneWardenShardCount(leader), stoneWardenShardCount(mate)];
        if (replayCounts[0] !== 1 || replayCounts[1] !== 1) {
          throw new Error(`Stone Vault recovery replay changed shard count: ${replayCounts.join(',')}`);
        }
        console.log('STONE_VAULT_ITEM_RECOVERY_REPLAY_PASS both real clients retained exactly one shard');

        vaultDiagnostics.phase = 'runtime-cleanup';
        await waitFor('Stone Vault local runtime cleanup', () => {
          return rcon('dungeon status').includes('No active Stone Vault activity.');
        }, 10_000, 250);
        console.log(`Stone Vault completed with exactly one shard per real client; confirmedBossHits=${confirmedBossHits}.`);
        return;
      }

      const attacker = [leader, mate][attackIndex++ % 2];
      const confirmed = await attackEntityAndConfirmDamage(
        attacker,
        leader,
        boss.id,
        arenaCenter,
        'during Stone Vault boss combat'
      );
      if (confirmed) {
        confirmedBossHits++;
        vaultDiagnostics.confirmedBossHits = confirmedBossHits;
        vaultDiagnostics.confirmedHitsByPlayer[attacker.username] =
          (vaultDiagnostics.confirmedHitsByPlayer[attacker.username] || 0) + 1;
        console.log(`${attacker.username} server-confirmed Stone Warden hit count=${confirmedBossHits}`);
        await sleep(500);
      } else {
        await sleep(150);
      }
    }

    throw new Error(
      `Stone Vault boss did not complete; confirmedBossHits=${confirmedBossHits} `
      + `boss=${rcon('boss status')} dungeon=${rcon('dungeon status')}`
    );
  } catch (error) {
    writeFailureDiagnostics(error, leader, mate, arenaCenter);
    throw error;
  } finally {
    for (const tracked of [leader, mate].filter(Boolean)) {
      try {
        if (tracked.bot._client && !tracked.bot._client.ended) {
          tracked.bot.quit('Stone Vault E2E complete');
        }
      } catch (error) {
        console.error(`Failed to close ${tracked.username}:`, error);
      }
    }
  }
}

main()
  .then(() => {
    console.log('STONE_VAULT_PARTY_E2E_CLIENT_PASS');
    process.exit(0);
  })
  .catch((error) => {
    console.error('STONE_VAULT_PARTY_E2E_CLIENT_FAIL');
    console.error(error.stack || error);
    process.exit(1);
  });
