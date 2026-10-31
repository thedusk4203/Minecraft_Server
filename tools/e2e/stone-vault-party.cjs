'use strict';

const { execFileSync } = require('node:child_process');
const mineflayer = require('mineflayer');

const LEADER_NAME = 'StoneVaultLead';
const MATE_NAME = 'StoneVaultMate';
const BOT_HOST = process.env.E2E_BOT_HOST || '127.0.0.1';
const BOT_PORT = Number(process.env.E2E_BOT_PORT || '25568');
const BOT_VERSION = process.env.E2E_BOT_VERSION || '26.1';
const COMPOSE_FILE = process.env.E2E_COMPOSE_FILE || 'compose.e2e.yaml';
const PAPER_SERVICE = process.env.E2E_PAPER_SERVICE || 'paper-e2e';
const ATTACK_REACH = 3.6;
const PARTY_CONVERGENCE_DISTANCE = 9.0;
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

async function convergePartyClients(leader, mate) {
  if (horizontalDistance(leader.bot.entity.position, mate.bot.entity.position) <= PARTY_CONVERGENCE_DISTANCE) {
    return true;
  }
  const leaderHigher = leader.bot.entity.position.y >= mate.bot.entity.position.y;
  const attempts = leaderHigher
    ? [[leader, mate], [mate, leader]]
    : [[mate, leader], [leader, mate]];
  for (const [mover, target] of attempts) {
    const converged = await walkToward(
      mover,
      () => target.bot.entity?.position,
      PARTY_CONVERGENCE_DISTANCE,
      10_000
    );
    if (converged) {
      return true;
    }
  }
  return horizontalDistance(leader.bot.entity.position, mate.bot.entity.position) <= PARTY_CONVERGENCE_DISTANCE;
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

async function killEntityIds(attackers, observer, entityIds, description) {
  for (let index = 0; index < entityIds.length; index++) {
    const entityId = entityIds[index];
    const attacker = attackers[index % attackers.length];
    const deadline = Date.now() + 18_000;
    while (Date.now() < deadline) {
      for (const tracked of attackers) {
        assertConnected(tracked, `while clearing ${description}`);
      }
      const target = observer.bot.entities[entityId] || attacker.bot.entities[entityId];
      if (!target) {
        break;
      }
      const inReach = await walkToward(
        attacker,
        () => attacker.bot.entities[entityId]?.position || observer.bot.entities[entityId]?.position,
        ATTACK_REACH,
        2_500
      );
      const current = attacker.bot.entities[entityId] || observer.bot.entities[entityId];
      if (!inReach || !current) {
        await sleep(150);
        continue;
      }
      await attacker.bot.lookAt(current.position.offset(0, 0.9, 0), true);
      attacker.bot.attack(current, true);
      await sleep(450);
    }
    if (observer.bot.entities[entityId] || attacker.bot.entities[entityId]) {
      throw new Error(`Could not clear ${description} entity id=${entityId}`);
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
        `Stone Vault party could not converge within start boundary; distance=${horizontalDistance(
          leader.bot.entity.position,
          mate.bot.entity.position
        ).toFixed(2)}`
      );
    }
    console.log(`Stone Vault party converged; distance=${horizontalDistance(
      leader.bot.entity.position,
      mate.bot.entity.position
    ).toFixed(2)} blocks.`);

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
    await killEntityIds([leader, mate], leader, outerIds, 'Outer Seal');

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
    await killEntityIds([mate, leader], leader, innerIds, 'Inner Guard');

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
        console.log('Stone Vault completed with shard visible to both real clients.');
        return;
      }

      const attacker = [leader, mate][attackIndex++ % 2];
      const target = attacker.bot.entities[boss.id] || leader.bot.entities[boss.id];
      if (!target) {
        await sleep(200);
        continue;
      }
      const inReach = await walkToward(
        attacker,
        () => attacker.bot.entities[boss.id]?.position || leader.bot.entities[boss.id]?.position,
        ATTACK_REACH,
        2_500
      );
      const current = attacker.bot.entities[boss.id] || leader.bot.entities[boss.id];
      if (!inReach || !current) {
        await sleep(150);
        continue;
      }
      await attacker.bot.lookAt(current.position.offset(0, 1.0, 0), true);
      attacker.bot.attack(current, true);
      await sleep(500);
    }

    throw new Error(`Stone Vault boss did not complete; boss=${rcon('boss status')} dungeon=${rcon('dungeon status')}`);
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
