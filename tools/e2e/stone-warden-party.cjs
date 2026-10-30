'use strict';

const { execFileSync } = require('node:child_process');
const mineflayer = require('mineflayer');

const LEADER_NAME = 'StoneWardenLead';
const MATE_NAME = 'StoneWardenMate';
const BOT_HOST = process.env.E2E_BOT_HOST || '127.0.0.1';
const BOT_PORT = Number(process.env.E2E_BOT_PORT || '25568');
const BOT_VERSION = process.env.E2E_BOT_VERSION || '26.1';
const COMPOSE_FILE = process.env.E2E_COMPOSE_FILE || 'compose.e2e.yaml';
const PAPER_SERVICE = process.env.E2E_PAPER_SERVICE || 'paper-e2e';
const ATTACK_REACH = 3.6;
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

function isIronGolem(entity) {
  if (!entity) {
    return false;
  }
  const name = String(entity.name || '').toLowerCase();
  const displayName = String(entity.displayName || '').toLowerCase();
  return name === 'iron_golem' || name === 'irongolem' || displayName.includes('iron golem');
}

function horizontalDistance(first, second) {
  const dx = first.x - second.x;
  const dz = first.z - second.z;
  return Math.sqrt(dx * dx + dz * dz);
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
  bot.setControlState('sprint', true);
  try {
    while (Date.now() < deadline) {
      assertConnected(tracked, 'while moving client-side');
      const target = typeof targetPosition === 'function' ? targetPosition() : targetPosition;
      if (!target) {
        return false;
      }
      if (horizontalDistance(bot.entity.position, target) <= stopDistance) {
        return true;
      }

      await bot.lookAt(target.offset(0, 0.8, 0), true);
      bot.setControlState('forward', true);
      if (bot.entity.onGround && target.y - bot.entity.position.y > 0.8) {
        bot.setControlState('jump', true);
      } else {
        bot.setControlState('jump', false);
      }
      await sleep(125);
    }
    return false;
  } finally {
    bot.setControlState('forward', false);
    bot.setControlState('jump', false);
    bot.setControlState('sprint', false);
  }
}

async function keepNearStart(tracked, start) {
  assertConnected(tracked, 'during party mechanic verification');
  const { bot } = tracked;
  const displacedHorizontally = horizontalDistance(bot.entity.position, start) > 10;
  const displacedVertically = Math.abs(bot.entity.position.y - start.y) > 5;
  if (displacedHorizontally || displacedVertically) {
    await walkToward(tracked, start, 5.5, 2_500);
  }
}

async function main() {
  const leader = createTrackedBot(LEADER_NAME);
  let mate = null;

  try {
    await awaitSpawn(leader);
    console.log(`${LEADER_NAME} spawned; waiting for Paper connection throttle window to clear.`);
    await sleep(CONNECTION_THROTTLE_CLEAR_MS);

    mate = createTrackedBot(MATE_NAME);
    await awaitSpawn(mate);
    console.log(`Party bots spawned: ${LEADER_NAME}, ${MATE_NAME} via ${BOT_VERSION} -> ViaProxy -> Paper 26.3`);

    // Server spawn-radius may place two fresh players more than the encounter's
    // 12-block party-start boundary apart. Converge using only client-authored
    // movement; do not use RCON teleport, which would weaken the network E2E.
    const converged = await walkToward(
      mate,
      () => leader.bot.entity?.position,
      5.0,
      12_000
    );
    if (!converged) {
      throw new Error(
        `Party clients could not converge within start boundary; distance=${horizontalDistance(
          leader.bot.entity.position,
          mate.bot.entity.position
        ).toFixed(2)}`
      );
    }
    assertConnected(leader, 'before party start');
    assertConnected(mate, 'before party start');
    console.log(`Party clients converged; distance=${horizontalDistance(
      leader.bot.entity.position,
      mate.bot.entity.position
    ).toFixed(2)} blocks.`);

    const leaderStart = leader.bot.entity.position.clone();
    const mateStart = mate.bot.entity.position.clone();
    const initialEntityIds = new Set(Object.keys(leader.bot.entities));

    for (const name of [LEADER_NAME, MATE_NAME]) {
      rcon(`effect give ${name} minecraft:resistance 120 4 true`);
      rcon(`effect give ${name} minecraft:regeneration 120 4 true`);
    }

    leader.bot.chat(`/boss start ${MATE_NAME}`);

    const boss = await waitFor('party Stone Warden iron golem entity', () => {
      return Object.values(leader.bot.entities).find((entity) => {
        return !initialEntityIds.has(String(entity.id))
          && isIronGolem(entity)
          && entity.position.distanceTo(leader.bot.entity.position) <= 32;
      });
    }, 25_000, 100);

    console.log(`Party Stone Warden entity observed: id=${boss.id}`);

    const active = rcon('boss status');
    if (!active.includes('Active Stone Warden encounters: 1')) {
      throw new Error(`Party boss was not active after start: ${active}`);
    }

    const mechanicDeadline = Date.now() + 16_000;
    while (Date.now() < mechanicDeadline) {
      await keepNearStart(leader, leaderStart);
      await keepNearStart(mate, mateStart);
      await sleep(250);
    }

    assertConnected(leader, 'after party mechanic window');
    assertConnected(mate, 'after party mechanic window');
    console.log('Both party clients remained connected through the mechanic window.');

    // Keep the mate present and bound while the leader drives the terminal damage.
    // This validates the multi-participant run without making completion depend on
    // attack scheduling from two automation clients at exactly the same tick.
    rcon(`effect give ${LEADER_NAME} minecraft:strength 60 20 true`);

    const killDeadline = Date.now() + 35_000;
    while (Date.now() < killDeadline) {
      assertConnected(leader, 'during party kill verification');
      assertConnected(mate, 'during party kill verification');

      const status = rcon('boss status');
      if (status.includes('No active Stone Warden encounter.')) {
        console.log('Party Stone Warden runtime reports no active encounter after client attacks.');
        await sleep(1_000);
        return;
      }

      const current = leader.bot.entities[boss.id];
      if (!current) {
        await sleep(250);
        continue;
      }

      const inReach = await walkToward(
        leader,
        () => leader.bot.entities[boss.id]?.position,
        ATTACK_REACH,
        2_500
      );
      const target = leader.bot.entities[boss.id];
      if (!inReach || !target) {
        await sleep(200);
        continue;
      }

      await leader.bot.lookAt(target.position.offset(0, 1.0, 0), true);
      leader.bot.attack(target, true);
      await sleep(550);
    }

    throw new Error(`Party Stone Warden was not completed within kill deadline; status=${rcon('boss status')}`);
  } finally {
    for (const tracked of [leader, mate].filter(Boolean)) {
      try {
        if (tracked.bot._client && !tracked.bot._client.ended) {
          tracked.bot.quit('Party E2E complete');
        }
      } catch (error) {
        console.error(`Failed to close ${tracked.username}:`, error);
      }
    }
  }
}

main()
  .then(() => {
    console.log('STONE_WARDEN_PARTY_E2E_CLIENT_PASS');
    process.exit(0);
  })
  .catch((error) => {
    console.error('STONE_WARDEN_PARTY_E2E_CLIENT_FAIL');
    console.error(error.stack || error);
    process.exit(1);
  });
