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
const ARENA_SAFE_VERTICAL = 6.0;
const PARTY_CONVERGENCE_DISTANCE = 10.0;
const CONNECTION_THROTTLE_CLEAR_MS = 5_500;

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

function hasStoneWardenShard(tracked) {
  return tracked.bot.inventory.items().some((item) => item.name === 'amethyst_shard');
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

      const target = bot.entities[entityId] || observer.bot.entities[entityId];
      if (!target?.position) {
        return false;
      }

      const distance = bot.entity.position.distanceTo(target.position);
      if (distance <= MELEE_DISTANCE && bot.entities[entityId]) {
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
  const inReach = await walkIntoEntityMeleeRange(tracked, observer, entityId, arenaCenter, 3_500);
  const target = bot.entities[entityId];
  if (!inReach || !target) {
    return false;
  }

  if (!insideSafeArena(bot.entity.position, arenaCenter)) {
    return false;
  }
  if (horizontalDistance(bot.entity.position, arenaCenter) > ARENA_RADIUS) {
    throw new Error(`${tracked.username} left the Stone Vault runtime arena ${context}`);
  }
  if (bot.entity.position.distanceTo(target.position) > MELEE_DISTANCE) {
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

  bot.attack(target, true);
  return confirmedDamage;
}

async function pathfindToward(tracked, targetTracked, stopDistance, timeoutMs) {
  const { bot, username } = tracked;
  assertConnected(tracked, 'before pathfinding convergence');
  assertConnected(targetTracked, 'before pathfinding convergence target');

  const target = targetTracked.bot.entity.position.clone();
  const movements = new Movements(bot);
  movements.canDig = false;
  movements.allow1by1towers = false;
  bot.pathfinder.setMovements(movements);

  const goalRadius = Math.max(2, Math.floor(stopDistance - 1));
  const goal = new GoalNear(
    Math.floor(target.x),
    Math.floor(target.y),
    Math.floor(target.z),
    goalRadius
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
  return partyDistance(bot.entity.position, targetTracked.bot.entity.position) <= stopDistance;
}

async function convergePartyClients(leader, mate) {
  assertConnected(leader, 'before Stone Vault convergence');
  assertConnected(mate, 'before Stone Vault convergence');

  if (partyDistance(leader.bot.entity.position, mate.bot.entity.position) <= PARTY_CONVERGENCE_DISTANCE) {
    return true;
  }

  const leaderHigher = leader.bot.entity.position.y >= mate.bot.entity.position.y;
  const attempts = leaderHigher
    ? [[leader, mate], [mate, leader]]
    : [[mate, leader], [leader, mate]];

  for (const [mover, target] of attempts) {
    const before = partyDistance(leader.bot.entity.position, mate.bot.entity.position);
    console.log(
      `Stone Vault convergence mover=${mover.username} target=${target.username} distance=${before.toFixed(2)}`
    );

    if (await pathfindToward(mover, target, PARTY_CONVERGENCE_DISTANCE, 20_000)) {
      return true;
    }

    await walkToward(
      mover,
      () => target.bot.entity?.position,
      PARTY_CONVERGENCE_DISTANCE,
      7_500
    );
    if (partyDistance(leader.bot.entity.position, mate.bot.entity.position) <= PARTY_CONVERGENCE_DISTANCE) {
      return true;
    }
  }

  return partyDistance(leader.bot.entity.position, mate.bot.entity.position) <= PARTY_CONVERGENCE_DISTANCE;
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
  for (let index = 0; index < entityIds.length; index++) {
    const entityId = entityIds[index];
    const attacker = attackers[index % attackers.length];
    const deadline = Date.now() + 18_000;
    let confirmedHits = 0;
    while (Date.now() < deadline) {
      for (const tracked of attackers) {
        assertConnected(tracked, `while clearing ${description}`);
      }
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
        console.log(`${attacker.username} server-confirmed ${description} hit entity=${entityId} count=${confirmedHits}`);
        await sleep(450);
      } else {
        await sleep(150);
      }
    }
    if (observer.bot.entities[entityId] || attacker.bot.entities[entityId]) {
      throw new Error(
        `Could not clear ${description} entity id=${entityId}; confirmedHits=${confirmedHits}; `
        + `attackerArenaDistance=${horizontalDistance(attacker.bot.entity.position, arenaCenter).toFixed(2)}`
      );
    }
  }
}

async function main() {
  const leader = createTrackedBot(LEADER_NAME);
  let mate = null;

  try {
    await awaitSpawn(leader);
    await sleep(CONNECTION_THROTTLE_CLEAR_MS);
    mate = createTrackedBot(MATE_NAME);
    await awaitSpawn(mate);

    const converged = await convergePartyClients(leader, mate);
    if (!converged) {
      throw new Error(
        `Stone Vault party could not converge within start boundary; distance=${partyDistance(
          leader.bot.entity.position,
          mate.bot.entity.position
        ).toFixed(2)}`
      );
    }
    console.log(`Stone Vault party converged; distance=${partyDistance(
      leader.bot.entity.position,
      mate.bot.entity.position
    ).toFixed(2)} blocks.`);

    const arenaCenter = leader.bot.entity.position.clone();
    const seenIds = new Set(Object.keys(leader.bot.entities));
    for (const name of [LEADER_NAME, MATE_NAME]) {
      rcon(`effect give ${name} minecraft:resistance 180 4 true`);
      rcon(`effect give ${name} minecraft:regeneration 180 4 true`);
      rcon(`effect give ${name} minecraft:strength 180 20 true`);
    }

    leader.bot.chat(`/dungeon start ${MATE_NAME}`);

    await waitFor('Stone Vault runtime start', () => {
      const status = rcon('dungeon status');
      return status.includes('Active Stone Vault activities: 1') && status.includes('beat=OUTER_SEAL');
    }, 25_000, 250);

    const outerIds = await waitForWave(
      leader,
      seenIds,
      4,
      isZombie,
      'four Outer Seal sentinels'
    );
    console.log(`Outer Seal observed: ${outerIds.join(',')}`);
    await killEntityIds([leader, mate], leader, outerIds, 'Outer Seal', arenaCenter);

    await waitFor('Inner Guard durable/runtime transition', () => {
      const status = rcon('dungeon status');
      return status.includes('beat=INNER_GUARD');
    }, 20_000, 250);

    const innerIds = await waitForWave(
      leader,
      seenIds,
      4,
      (entity) => isZombie(entity) || isSkeleton(entity),
      'four Inner Guard mobs'
    );
    console.log(`Inner Guard observed: ${innerIds.join(',')}`);
    await killEntityIds([mate, leader], leader, innerIds, 'Inner Guard', arenaCenter);

    const boss = await waitFor('Stone Warden after Stone Vault waves', () => {
      return nearbyNewEntities(leader, seenIds, isIronGolem).at(0) || null;
    }, 25_000, 100);
    seenIds.add(String(boss.id));
    console.log(`Stone Vault Stone Warden observed: id=${boss.id}`);

    await waitFor('Stone Vault boss activation status', () => {
      const status = rcon('dungeon status');
      return status.includes('beat=BOSS') && status.includes('bossActive=true');
    }, 15_000, 250);

    const killDeadline = Date.now() + 35_000;
    let attackIndex = 0;
    let confirmedBossHits = 0;
    while (Date.now() < killDeadline) {
      for (const tracked of [leader, mate]) {
        assertConnected(tracked, 'during Stone Vault boss combat');
      }
      const status = rcon('boss status');
      if (status.includes('No active Stone Warden encounter.')) {
        await waitFor(
          'Stone Warden Shard in both Stone Vault client inventories',
          () => hasStoneWardenShard(leader) && hasStoneWardenShard(mate),
          20_000,
          250
        );
        await waitFor('Stone Vault local runtime cleanup', () => {
          return rcon('dungeon status').includes('No active Stone Vault activity.');
        }, 10_000, 250);
        console.log(`Stone Vault completed with shard visible to both real clients; confirmedBossHits=${confirmedBossHits}.`);
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
