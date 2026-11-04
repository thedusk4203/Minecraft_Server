'use strict';

// Fault-injection acceptance intentionally runs AFTER real-client combat/rewards.
// Only the separately-built E2E plugin can receive /vaultfault commands.
const { execFileSync } = require('node:child_process');
const mineflayer = require('mineflayer');

const HOST = process.env.E2E_BOT_HOST || '127.0.0.1';
const PORT = Number(process.env.E2E_BOT_PORT || '25568');
const VERSION = process.env.E2E_BOT_VERSION || '26.1';
const COMPOSE = process.env.E2E_COMPOSE_FILE || 'compose.e2e.yaml';
const PAPER = process.env.E2E_PAPER_SERVICE || 'paper-e2e';
const DB = process.env.E2E_POSTGRES_SERVICE || 'postgres-e2e';
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function docker(service, ...args) {
  return execFileSync('docker', ['compose', '-f', COMPOSE, 'exec', '-T', service, ...args], {
    encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 15_000
  }).trim();
}

function rcon(command) {
  return docker(PAPER, 'rcon-cli', command);
}

function fault(action, runId) {
  const result = rcon(`vaultfault ${action} ${runId}`);
  if (result.includes('E2E_FAULT_ERROR') || !result.includes('E2E_FAULT_')) {
    throw new Error(`Fault injection failed action=${action}: ${result}`);
  }
  console.log(result);
  return result;
}

function sql(query) {
  return docker(DB, 'psql', '-U', 'minecraft', '-d', 'minecraft', '-tAc', query);
}

async function waitFor(description, predicate, timeoutMs = 35_000) {
  const limit = Date.now() + timeoutMs;
  let lastError = null;
  while (Date.now() < limit) {
    try {
      const result = predicate();
      if (result) return result;
    } catch (error) {
      lastError = error;
    }
    await sleep(250);
  }
  throw new Error(`Timed out waiting for ${description}${lastError ? ': ' + lastError.message : ''}`);
}

function terminalRow(runId) {
  return sql(`SELECT e.status || '|' || coalesce(e.terminal_reason, '') || '|' || a.current_beat FROM encounter_run e JOIN structured_activity_run a ON a.run_id = e.run_id WHERE e.run_id = '${runId}'::uuid`);
}

function assertDurableFailed(runId, reason, beat) {
  const state = terminalRow(runId);
  if (state !== `FAILED|${reason}|${beat}`) {
    throw new Error(`Unexpected durable state run=${runId}: ${state}`);
  }
  const counts = sql(`SELECT (SELECT count(*) FROM encounter_participant WHERE run_id = '${runId}'::uuid), (SELECT count(*) FROM encounter_reward WHERE run_id = '${runId}'::uuid), (SELECT count(*) FROM encounter_reward_item i JOIN encounter_reward r ON r.reward_id = i.reward_id WHERE r.run_id = '${runId}'::uuid), (SELECT count(*) FROM audit_event WHERE idempotency_key = 'encounter:run:${runId}:terminal' AND event_type='encounter.run_failed')`);
  if (counts !== '1|0|0|1') {
    throw new Error(`Invalid participant/reward/terminal audit counts for ${runId}: ${counts}`);
  }
  if (!rcon('dungeon status').includes('No active Stone Vault activity.')
      || !rcon('boss status').includes('No active Stone Warden encounter.')) {
    throw new Error(`Runtime not cleaned after ${reason} for ${runId}`);
  }
  console.log(`E2E_FAULT_TERMINAL_PASS run=${runId} state=${state} counts=${counts}`);
}

function createBot(username) {
  const bot = mineflayer.createBot({
    host: HOST, port: PORT, version: VERSION, username,
    auth: 'offline', hideErrors: false
  });
  const originalWrite = bot._client.write.bind(bot._client);
  const movements = new Set(['position', 'position_look', 'look', 'flying']);
  bot._client.write = (name, packet) => {
    const result = originalWrite(name, packet);
    if (movements.has(name)) originalWrite('tick_end', {});
    return result;
  };
  bot.on('error', error => console.error(`${username} error:`, error));
  bot.on('kicked', reason => console.error(`${username} kicked:`, reason));
  return bot;
}

async function spawnBot(name) {
  const bot = createBot(name);
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`Spawn timed out: ${name}`)), 30_000);
    bot.once('spawn', () => { clearTimeout(timer); resolve(); });
    bot.once('end', (reason) => { clearTimeout(timer); reject(new Error(`${name} ended before spawn: ${reason}`)); });
  });
  return bot;
}

async function quitBot(bot) {
  if (!bot || bot._client?.ended) return;
  const end = new Promise(resolve => {
    bot.once('end', resolve);
    setTimeout(resolve, 8_000);
  });
  bot.quit('Stone Vault fault acceptance done');
  await end;
}

async function beginVault(bot) {
  if (!rcon('dungeon status').includes('No active Stone Vault activity.')) {
    throw new Error('Stone Vault was not idle before fault test');
  }
  bot.chat('/dungeon start');
  const runId = await waitFor('Stone Vault start', () => {
    const text = rcon('dungeon status');
    if (!text.includes('beat=OUTER_SEAL') || !text.includes('participants=1')) return null;
    return text.match(/run=([0-9a-f-]{36})/)?.[1] || null;
  }, 25_000);
  console.log(`E2E_FAULT_RUN_STARTED player=${bot.username} run=${runId}`);
  return runId;
}

async function missingMobScenario() {
  let bot;
  let runId;
  try {
    bot = await spawnBot('VaultMissingMob');
    runId = await beginVault(bot);
    fault('armduplicate', runId);
    fault('killone', runId); // Captures a real death event, then replays it immediately.
    fault('remove', runId); // Removes another wave mob with NO death event.
    await waitFor('unexpected missing wave mob terminal failure', () =>
      terminalRow(runId) === 'FAILED|WAVE_ENTITY_MISSING|1', 40_000);
    assertDurableFailed(runId, 'WAVE_ENTITY_MISSING', 1);

    fault('replay', runId); // Replay old captured event after terminal cleanup.
    await sleep(1_000);
    assertDurableFailed(runId, 'WAVE_ENTITY_MISSING', 1);
    console.log('STONE_VAULT_MISSING_MOB_AND_STALE_CALLBACK_E2E_PASS');
  } catch (error) {
    console.error('STONE_VAULT_MISSING_MOB_E2E_DIAGNOSTIC', JSON.stringify({
      runId, error: String(error.stack || error),
      dungeon: (() => { try { return rcon('dungeon status'); } catch (e) { return String(e); } })()
    }));
    throw error;
  } finally {
    await quitBot(bot);
    await sleep(5_500); // Preserve normal Paper connection throttle.
  }
}

async function bossRejectionScenario() {
  let bot;
  let runId;
  try {
    bot = await spawnBot('VaultRejectBoss');
    runId = await beginVault(bot);
    fault('rejectboss', runId);

    // Test-only fault injector triggers normal EntityDeathEvent callbacks;
    // existing positive Stone Vault E2E still requires actual client attacks.
    fault('clearwave', runId);
    await waitFor('Inner Guard beat', () =>
      rcon('dungeon status').includes('beat=INNER_GUARD'), 30_000);
    fault('clearwave', runId);

    await waitFor('boss activation rejection', () =>
      terminalRow(runId) === 'FAILED|BOSS_ACTIVATION_FAILED|3', 40_000);
    assertDurableFailed(runId, 'BOSS_ACTIVATION_FAILED', 3);
    fault('replay', runId);
    await sleep(1_000);
    assertDurableFailed(runId, 'BOSS_ACTIVATION_FAILED', 3);
    console.log('STONE_VAULT_BOSS_REJECTION_AND_REPLAY_E2E_PASS');
  } catch (error) {
    console.error('STONE_VAULT_BOSS_REJECTION_E2E_DIAGNOSTIC', JSON.stringify({
      runId, error: String(error.stack || error),
      dungeon: (() => { try { return rcon('dungeon status'); } catch (e) { return String(e); } })(),
      boss: (() => { try { return rcon('boss status'); } catch (e) { return String(e); } })()
    }));
    throw error;
  } finally {
    await quitBot(bot);
    await sleep(5_500);
  }
}

async function main() {
  const plugins = rcon('plugins');
  if (!plugins.includes('StoneVaultE2EFaults')) {
    throw new Error('Expected dedicated E2E fault helper plugin; never use production bypasses');
  }
  await missingMobScenario();
  await bossRejectionScenario();
  console.log('STONE_VAULT_FAILURE_PATH_E2E_PASS');
}

main().then(() => process.exit(0)).catch(error => {
  console.error('STONE_VAULT_FAILURE_PATH_E2E_FAIL');
  console.error(error.stack || error);
  process.exit(1);
});
