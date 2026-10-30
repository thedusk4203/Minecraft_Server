'use strict';

const { execFileSync } = require('node:child_process');
const mineflayer = require('mineflayer');

const BOT_NAME = 'StoneWardenE2E';
const BOT_HOST = process.env.E2E_BOT_HOST || '127.0.0.1';
const BOT_PORT = Number(process.env.E2E_BOT_PORT || '25568');
const BOT_VERSION = process.env.E2E_BOT_VERSION || '26.1';
const COMPOSE_FILE = process.env.E2E_COMPOSE_FILE || 'compose.e2e.yaml';
const PAPER_SERVICE = process.env.E2E_PAPER_SERVICE || 'paper-e2e';
const ATTACK_REACH = 3.6;

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
  return name === 'iron_golem'
    || name === 'irongolem'
    || displayName.includes('iron golem');
}

function horizontalDistance(first, second) {
  const dx = first.x - second.x;
  const dz = first.z - second.z;
  return Math.sqrt(dx * dx + dz * dz);
}

function assertConnected(bot, kicked, context) {
  if (kicked()) {
    throw new Error(`Bot was kicked ${context}: ${JSON.stringify(kicked())}`);
  }
  if (!bot.entity) {
    throw new Error(`Bot entity disappeared ${context}`);
  }
}

async function walkToward(bot, kicked, targetPosition, stopDistance, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  bot.setControlState('sprint', true);
  try {
    while (Date.now() < deadline) {
      assertConnected(bot, kicked, 'while moving client-side');
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

async function main() {
  const bot = mineflayer.createBot({
    host: BOT_HOST,
    port: BOT_PORT,
    version: BOT_VERSION,
    username: BOT_NAME,
    auth: 'offline',
    hideErrors: false
  });

  let kickedReason = null;
  const kicked = () => kickedReason;
  bot.on('kicked', (reason) => {
    kickedReason = reason;
    console.error('Bot kicked:', reason);
  });
  bot.on('error', (error) => {
    console.error('Bot error:', error);
  });

  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Bot did not spawn within 30 seconds')), 30_000);
    bot.once('spawn', () => {
      clearTimeout(timeout);
      resolve();
    });
    bot.once('end', (reason) => {
      clearTimeout(timeout);
      reject(new Error(`Bot connection ended before spawn: ${reason}`));
    });
  });

  console.log(`E2E bot spawned as ${bot.username} via ${BOT_VERSION} -> ViaProxy -> Paper 26.3`);
  const initialEntityIds = new Set(Object.keys(bot.entities));

  // These effects keep the verification deterministic without moving the player
  // server-side. Position is always produced by the real client physics path.
  rcon(`effect give ${BOT_NAME} minecraft:resistance 120 4 true`);
  rcon(`effect give ${BOT_NAME} minecraft:regeneration 120 4 true`);

  const start = bot.entity.position.clone();
  bot.chat('/boss start');

  const boss = await waitFor('new Stone Warden iron golem entity', () => {
    return Object.values(bot.entities).find((entity) => {
      return !initialEntityIds.has(String(entity.id))
        && isIronGolem(entity)
        && entity.position.distanceTo(bot.entity.position) <= 32;
    });
  }, 25_000, 100);

  console.log(`Stone Warden entity observed: id=${boss.id}`);

  // Let both telegraphed mechanics execute. If knockback pushes the client toward
  // the arena boundary, walk back using normal Mineflayer movement packets; never
  // RCON-teleport a connected protocol-translated client.
  const mechanicDeadline = Date.now() + 9_000;
  while (Date.now() < mechanicDeadline) {
    assertConnected(bot, kicked, 'during mechanic verification');
    const displacedHorizontally = horizontalDistance(bot.entity.position, start) > 10;
    const displacedVertically = Math.abs(bot.entity.position.y - start.y) > 5;
    if (displacedHorizontally || displacedVertically) {
      await walkToward(bot, kicked, start, 5.5, 2_500);
    }
    await sleep(250);
  }

  const activeBeforeKill = rcon('boss status');
  if (!activeBeforeKill.includes('Active Stone Warden encounters: 1')) {
    throw new Error(`Boss did not remain active through mechanic window: ${activeBeforeKill}`);
  }

  // Accelerate only the kill portion. Damage still originates from the real client,
  // so participant binding, arena authorization and normal melee reach are exercised.
  rcon(`effect give ${BOT_NAME} minecraft:strength 60 20 true`);

  const killDeadline = Date.now() + 35_000;
  while (Date.now() < killDeadline) {
    assertConnected(bot, kicked, 'during kill verification');
    const status = rcon('boss status');
    if (status.includes('No active Stone Warden encounter.')) {
      console.log('Stone Warden runtime reports no active encounter after client attacks.');
      await sleep(1_000);
      bot.quit('E2E complete');
      return;
    }

    const current = bot.entities[boss.id];
    if (!current) {
      await sleep(250);
      continue;
    }

    const inReach = await walkToward(
      bot,
      kicked,
      () => bot.entities[boss.id]?.position,
      ATTACK_REACH,
      2_500
    );
    const target = bot.entities[boss.id];
    if (!inReach || !target) {
      await sleep(200);
      continue;
    }

    await bot.lookAt(target.position.offset(0, 1.0, 0), true);
    bot.attack(target, true);
    await sleep(550);
  }

  throw new Error(`Stone Warden was not completed within kill deadline; status=${rcon('boss status')}`);
}

main()
  .then(() => {
    console.log('STONE_WARDEN_E2E_CLIENT_PASS');
    process.exit(0);
  })
  .catch((error) => {
    console.error('STONE_WARDEN_E2E_CLIENT_FAIL');
    console.error(error.stack || error);
    process.exit(1);
  });
