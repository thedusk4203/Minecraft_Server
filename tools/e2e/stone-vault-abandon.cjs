'use strict';

const { execFileSync } = require('node:child_process');
const mineflayer = require('mineflayer');

const PLAYER = 'VaultLossE2E';
const HOST = process.env.E2E_BOT_HOST || '127.0.0.1';
const PORT = Number(process.env.E2E_BOT_PORT || '25568');
const VERSION = process.env.E2E_BOT_VERSION || '26.1';
const COMPOSE = process.env.E2E_COMPOSE_FILE || 'compose.e2e.yaml';
const PAPER = process.env.E2E_PAPER_SERVICE || 'paper-e2e';
const POSTGRES = process.env.E2E_POSTGRES_SERVICE || 'postgres-e2e';

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function docker(service, ...args) {
  return execFileSync('docker', ['compose', '-f', COMPOSE, 'exec', '-T', service, ...args], {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
    timeout: 12_000
  }).trim();
}

function rcon(command) {
  return docker(PAPER, 'rcon-cli', command);
}

function sql(statement) {
  return docker(POSTGRES, 'psql', '-U', 'minecraft', '-d', 'minecraft', '-tAc', statement);
}

async function waitFor(description, predicate, timeoutMs = 30_000) {
  const deadline = Date.now() + timeoutMs;
  let last = '';
  while (Date.now() < deadline) {
    try {
      const result = predicate();
      if (result) return result;
    } catch (error) {
      last = String(error.message || error);
    }
    await sleep(250);
  }
  throw new Error(`${description} timed out; last error=${last}`);
}

function createBot() {
  const bot = mineflayer.createBot({
    host: HOST, port: PORT, version: VERSION, username: PLAYER,
    auth: 'offline', hideErrors: false
  });
  // Same pinned Mineflayer 26.1 packet compatibility shim as positive E2Es.
  const originalWrite = bot._client.write.bind(bot._client);
  const movementPackets = new Set(['position', 'position_look', 'look', 'flying']);
  bot._client.write = (name, packet) => {
    const result = originalWrite(name, packet);
    if (movementPackets.has(name)) originalWrite('tick_end', {});
    return result;
  };
  bot.on('error', (error) => console.error(`${PLAYER} network error:`, error));
  bot.on('kicked', (reason) => console.error(`${PLAYER} kicked:`, reason));
  return bot;
}

async function spawnBot() {
  const bot = createBot();
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Client spawn timed out')), 30_000);
    bot.once('spawn', () => { clearTimeout(timer); resolve(); });
    bot.once('end', (reason) => { clearTimeout(timer); reject(new Error(`Client ended before spawn: ${reason}`)); });
  });
  return bot;
}

async function endBot(bot) {
  if (!bot || bot._client?.ended) return;
  const ended = new Promise((resolve) => {
    bot.once('end', resolve);
    setTimeout(resolve, 10_000);
  });
  bot.quit('Stone Vault failure-path E2E disconnect');
  await ended;
}

async function main() {
  let bot;
  let runId;
  try {
    if (!rcon('dungeon status').includes('No active Stone Vault activity.')) {
      throw new Error('Stone Vault must be idle before participant-loss E2E');
    }
    bot = await spawnBot();
    bot.chat('/dungeon start');
    runId = await waitFor('solo Stone Vault start', () => {
      const status = rcon('dungeon status');
      if (!status.includes('beat=OUTER_SEAL') || !status.includes('participants=1')) return null;
      return status.match(/run=([0-9a-f-]{36})/)?.[1] || null;
    }, 25_000);
    console.log(`Stone Vault started with a real client: run=${runId}`);

    const attached = sql(`SELECT count(*) FROM structured_activity_run WHERE run_id = '${runId}'::uuid AND activity_id = 'stone_vault'`);
    if (attached !== '1') throw new Error(`Expected one durable activity attachment; observed=${attached}`);

    await endBot(bot);
    bot = null;
    const terminal = await waitFor('Stone Vault participant-loss terminalization', () => {
      const row = sql(`SELECT status || '|' || coalesce(terminal_reason,'') FROM encounter_run WHERE run_id = '${runId}'::uuid`);
      return row.startsWith('FAILED|') ? row : null;
    }, 60_000);
    if (terminal !== 'FAILED|NO_ACTIVE_PARTICIPANTS') {
      throw new Error(`Unexpected terminal outcome for participant loss: ${terminal}`);
    }
    if (!rcon('dungeon status').includes('No active Stone Vault activity.')) {
      throw new Error('Stone Vault runtime was not cleaned after participant loss');
    }

    const counts = sql(`SELECT (SELECT count(*) FROM encounter_participant WHERE run_id = '${runId}'::uuid), (SELECT count(*) FROM encounter_reward WHERE run_id = '${runId}'::uuid), (SELECT count(*) FROM encounter_reward_item i JOIN encounter_reward r ON r.reward_id = i.reward_id WHERE r.run_id = '${runId}'::uuid)`);
    if (counts !== '1|0|0') throw new Error(`Invalid canonical participant/reward counts: ${counts}`);
    console.log(`Participant-loss durable state: run=${runId} terminal=${terminal} participants/rewards/items=${counts}`);

    // Reconnect same internal player; a terminal attempt must not reactivate or pay out.
    await sleep(5_500);
    bot = await spawnBot();
    await sleep(1_000);
    const statusAfterReconnect = sql(`SELECT status || '|' || coalesce(terminal_reason,'') FROM encounter_run WHERE run_id = '${runId}'::uuid`);
    const rewardsAfterReconnect = sql(`SELECT count(*) FROM encounter_reward WHERE run_id = '${runId}'::uuid`);
    if (statusAfterReconnect !== terminal || rewardsAfterReconnect !== '0') {
      throw new Error(`Reconnect changed terminal run: ${statusAfterReconnect} rewardRows=${rewardsAfterReconnect}`);
    }
    if (!rcon('dungeon status').includes('No active Stone Vault activity.')) {
      throw new Error('Reconnect reactivated a terminal Stone Vault runtime');
    }
    console.log('STONE_VAULT_PARTICIPANT_LOSS_E2E_PASS');
  } catch (error) {
    console.error('STONE_VAULT_PARTICIPANT_LOSS_E2E_DIAGNOSTIC', JSON.stringify({
      runId, dungeon: (() => { try { return rcon('dungeon status'); } catch (e) { return String(e); } })(),
      error: String(error.stack || error)
    }));
    throw error;
  } finally {
    if (bot) await endBot(bot);
  }
}

main().then(() => process.exit(0)).catch((error) => {
  console.error(error.stack || error);
  process.exit(1);
});
