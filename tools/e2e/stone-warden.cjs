'use strict';

const { execFileSync } = require('node:child_process');
const mineflayer = require('mineflayer');

const BOT_NAME = 'StoneWardenE2E';
const BOT_HOST = process.env.E2E_BOT_HOST || '127.0.0.1';
const BOT_PORT = Number(process.env.E2E_BOT_PORT || '25568');
const BOT_VERSION = process.env.E2E_BOT_VERSION || '26.1';
const COMPOSE_FILE = process.env.E2E_COMPOSE_FILE || 'compose.e2e.yaml';
const PAPER_SERVICE = process.env.E2E_PAPER_SERVICE || 'paper-e2e';

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

function teleportBotNear(entity) {
  const x = entity.position.x + 2.4;
  const y = entity.position.y;
  const z = entity.position.z;
  rcon(`tp ${BOT_NAME} ${x.toFixed(3)} ${y.toFixed(3)} ${z.toFixed(3)}`);
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

  let kicked = null;
  bot.on('kicked', (reason) => {
    kicked = reason;
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

  // Keep the real client inside the arena while allowing both telegraphed mechanics
  // to execute. Resistance prevents the verification bot dying from vanilla/mechanic damage.
  const mechanicDeadline = Date.now() + 9_000;
  while (Date.now() < mechanicDeadline) {
    if (kicked) {
      throw new Error(`Bot was kicked during mechanic verification: ${JSON.stringify(kicked)}`);
    }
    rcon(`tp ${BOT_NAME} ${start.x.toFixed(3)} ${start.y.toFixed(3)} ${start.z.toFixed(3)}`);
    await sleep(1_500);
  }

  const activeBeforeKill = rcon('boss status');
  if (!activeBeforeKill.includes('Active Stone Warden encounters: 1')) {
    throw new Error(`Boss did not remain active through mechanic window: ${activeBeforeKill}`);
  }

  // Accelerate only the kill portion. Damage still originates from the real client,
  // so participant binding / reach / damage authorization remain in the exercised path.
  rcon(`effect give ${BOT_NAME} minecraft:strength 60 20 true`);

  const killDeadline = Date.now() + 30_000;
  while (Date.now() < killDeadline) {
    const current = bot.entities[boss.id];
    const status = rcon('boss status');
    if (status.includes('No active Stone Warden encounter.')) {
      console.log('Stone Warden runtime reports no active encounter after client attacks.');
      await sleep(1_000);
      bot.quit('E2E complete');
      return;
    }
    if (!current) {
      await sleep(300);
      continue;
    }

    teleportBotNear(current);
    await sleep(250);
    await bot.lookAt(current.position.offset(0, 1.0, 0), true);
    bot.attack(current, true);
    await sleep(450);
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
