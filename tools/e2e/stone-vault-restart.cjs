'use strict';

// Real-client Stone Vault startup/restart acceptance on the isolated E2E stack.
// Never use RCON for encounter admission, damage, wave clears, or boss kills.
const { execFileSync } = require('node:child_process');
const mineflayer = require('mineflayer');

const BOT_NAME = 'VaultRestartE2E';
const BOT_HOST = process.env.E2E_BOT_HOST || '127.0.0.1';
const BOT_PORT = Number(process.env.E2E_BOT_PORT || '25568');
const BOT_VERSION = process.env.E2E_BOT_VERSION || '26.1';
const COMPOSE = process.env.E2E_COMPOSE_FILE || 'compose.e2e.yaml';
const PAPER = process.env.E2E_PAPER_SERVICE || 'paper-e2e';
const POSTGRES = process.env.E2E_POSTGRES_SERVICE || 'postgres-e2e';
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function compose(...args) {
  return execFileSync('docker', ['compose', '-f', COMPOSE, ...args], {
    encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 180_000
  }).trim();
}

function rcon(command) {
  return compose('exec', '-T', PAPER, 'rcon-cli', command);
}

function sql(statement) {
  return compose('exec', '-T', POSTGRES, 'psql', '-U', 'minecraft', '-d', 'minecraft', '-tAc', statement);
}

async function poll(label, condition, timeoutMs = 90_000) {
  const until = Date.now() + timeoutMs;
  let lastError = '';
  while (Date.now() < until) {
    try {
      const result = condition();
      if (result) return result;
    } catch (error) {
      lastError = String(error.message || error);
    }
    await sleep(500);
  }
  throw new Error(`Timeout waiting for ${label}; last error=${lastError}`);
}

async function joinClient() {
  const bot = mineflayer.createBot({
    host: BOT_HOST, port: BOT_PORT, version: BOT_VERSION,
    username: BOT_NAME, auth: 'offline', hideErrors: false
  });
  // Same network client compatibility shim as other pinned real-client E2Es.
  const originalWrite = bot._client.write.bind(bot._client);
  const movementPackets = new Set(['position', 'position_look', 'look', 'flying']);
  bot._client.write = (name, packet) => {
    const value = originalWrite(name, packet);
    if (movementPackets.has(name)) originalWrite('tick_end', {});
    return value;
  };
  bot.on('error', (error) => console.error('Stone Vault restart bot error:', error));
  bot.on('kicked', (reason) => console.error('Stone Vault restart bot kicked:', reason));
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Restart test client spawn timed out')), 30_000);
    bot.once('spawn', () => { clearTimeout(timeout); resolve(); });
    bot.once('end', (reason) => { clearTimeout(timeout); reject(new Error(`Client ended before spawn: ${reason}`)); });
  });
  return bot;
}

async function main() {
  let client;
  let runId;
  try {
    if (!rcon('dungeon status').includes('No active Stone Vault activity.')) {
      throw new Error('Stone Vault not idle before restart scenario');
    }
    client = await joinClient();
    client.chat('/dungeon start');
    runId = await poll('Stone Vault ACTIVE via normal client action', () => {
      const status = rcon('dungeon status');
      if (!status.includes('beat=OUTER_SEAL') || !status.includes('participants=1')) return null;
      return status.match(/run=([a-f0-9-]{36})/)?.[1] || null;
    }, 30_000);
    const persisted = sql(`SELECT e.status || '|' || a.activity_id || '|' || a.current_beat FROM encounter_run e JOIN structured_activity_run a ON a.run_id = e.run_id WHERE e.run_id = '${runId}'::uuid`);
    if (persisted !== 'ACTIVE|stone_vault|1') {
      throw new Error(`Wrong pre-restart run state: ${persisted}`);
    }
    console.log(`Stone Vault active before Paper restart: run=${runId} state=${persisted}`);

    // Restart only the ephemeral Paper E2E container; retain PostgreSQL and its volumes.
    compose('restart', '-t', '30', PAPER);
    await poll('Paper DB-backed plugin restart', () => rcon('mcserver health').includes('db=UP'), 180_000);
    const terminal = await poll('startup recovery of ACTIVE Stone Vault run', () => {
      const row = sql(`SELECT status || '|' || coalesce(terminal_reason, '') FROM encounter_run WHERE run_id = '${runId}'::uuid`);
      return row === 'ABANDONED|SERVER_RESTART' ? row : null;
    }, 30_000);

    const counts = sql(`SELECT (SELECT count(*) FROM encounter_participant WHERE run_id = '${runId}'::uuid), (SELECT count(*) FROM encounter_reward WHERE run_id = '${runId}'::uuid), (SELECT count(*) FROM encounter_reward_item i JOIN encounter_reward r ON i.reward_id=r.reward_id WHERE r.run_id = '${runId}'::uuid)`);
    if (counts !== '1|0|0') {
      throw new Error(`Restart abandoned run generated rewards or lost canonical participant: ${counts}`);
    }
    if (!rcon('dungeon status').includes('No active Stone Vault activity.')) {
      throw new Error('Stone Vault runtime was unexpectedly reconstructed after restart');
    }
    console.log(`STONE_VAULT_RESTART_E2E_PASS run=${runId} terminal=${terminal} participants/rewards/items=${counts}`);
  } catch (error) {
    console.error('STONE_VAULT_RESTART_E2E_DIAGNOSTIC', JSON.stringify({
      runId,
      error: String(error.stack || error),
      current: (() => { try { return runId ? sql(`SELECT status || '|' || coalesce(terminal_reason, '') FROM encounter_run WHERE run_id = '${runId}'::uuid`) : null; } catch (e) { return String(e); } })()
    }));
    throw error;
  } finally {
    if (client && !client._client?.ended) {
      try { client.quit('Stone Vault restart acceptance complete'); } catch (e) { console.error(e); }
    }
  }
}

main().then(() => process.exit(0)).catch(error => {
  console.error(error.stack || error);
  process.exit(1);
});
