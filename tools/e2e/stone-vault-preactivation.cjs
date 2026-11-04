'use strict';

// Deterministically disconnect a real client BETWEEN durable encounter start
// and runtime activation by holding only the activity-attach table lock.
const { execFileSync, spawn } = require('node:child_process');
const mineflayer = require('mineflayer');

const PLAYER = 'VaultStartGap';
const COMPOSE = process.env.E2E_COMPOSE_FILE || 'compose.e2e.yaml';
const PAPER = process.env.E2E_PAPER_SERVICE || 'paper-e2e';
const DB = process.env.E2E_POSTGRES_SERVICE || 'postgres-e2e';
const HOST = process.env.E2E_BOT_HOST || '127.0.0.1';
const PORT = Number(process.env.E2E_BOT_PORT || '25568');
const VERSION = process.env.E2E_BOT_VERSION || '26.1';
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

function docker(service, ...args) {
  return execFileSync('docker', ['compose', '-f', COMPOSE, 'exec', '-T', service, ...args], {
    encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 15_000
  }).trim();
}

function sql(query) {
  return docker(DB, 'psql', '-U', 'minecraft', '-d', 'minecraft', '-tAc', query);
}

function rcon(cmd) {
  return docker(PAPER, 'rcon-cli', cmd);
}

async function waitFor(name, fn, timeoutMs = 40_000) {
  const deadline = Date.now() + timeoutMs;
  let errorText = null;
  while (Date.now() < deadline) {
    try {
      const result = fn();
      if (result) return result;
    } catch (error) {
      errorText = error.message;
    }
    await sleep(200);
  }
  throw new Error(`Timed out waiting for ${name}${errorText ? ': ' + errorText : ''}`);
}

function startActivityLock() {
  // Separate PostgreSQL session keeps an ACCESS EXCLUSIVE table lock open
  // until explicitly released. It delays only the Stone Vault V9 attach,
  // letting encounter_run and encounter_participant commit first.
  const child = spawn('docker', [
    'compose', '-f', COMPOSE, 'exec', '-T', DB,
    'psql', '-U', 'minecraft', '-d', 'minecraft', '-At', '-v', 'ON_ERROR_STOP=1'
  ], { stdio: ['pipe', 'pipe', 'pipe'] });
  let stdout = '';
  let stderr = '';
  child.stdout.on('data', data => { stdout += data.toString(); });
  child.stderr.on('data', data => { stderr += data.toString(); });
  child.stdin.write("BEGIN; LOCK TABLE structured_activity_run IN ACCESS EXCLUSIVE MODE; SELECT 'E2E_ATTACH_LOCKED';\n");
  return {
    child,
    get stdout() { return stdout; },
    get stderr() { return stderr; },
    async release() {
      if (child.exitCode !== null) return;
      child.stdin.write('COMMIT;\n');
      child.stdin.end();
      await new Promise(resolve => {
        if (child.exitCode !== null) return resolve();
        child.once('close', resolve);
        setTimeout(() => {
          if (child.exitCode === null) child.kill('SIGTERM');
          resolve();
        }, 15_000);
      });
    }
  };
}

function createBot() {
  const bot = mineflayer.createBot({
    host: HOST, port: PORT, version: VERSION,
    username: PLAYER, auth: 'offline', hideErrors: false
  });
  // Preserve the same pinned protocol compatibility shim as positive E2Es.
  const originalWrite = bot._client.write.bind(bot._client);
  const names = new Set(['position', 'position_look', 'look', 'flying']);
  bot._client.write = (name, packet) => {
    const result = originalWrite(name, packet);
    if (names.has(name)) originalWrite('tick_end', {});
    return result;
  };
  bot.on('error', error => console.error(`${PLAYER} network error:`, error));
  bot.on('kicked', reason => console.error(`${PLAYER} kicked:`, reason));
  return bot;
}

async function awaitSpawn(bot) {
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Pre-activation client did not spawn')), 30_000);
    bot.once('spawn', () => { clearTimeout(timer); resolve(); });
    bot.once('end', reason => { clearTimeout(timer); reject(new Error(`Pre-activation client disconnected: ${reason}`)); });
  });
}

async function quitBot(bot) {
  if (!bot || bot._client?.ended) return;
  const end = new Promise(resolve => {
    bot.once('end', resolve);
    setTimeout(resolve, 10_000);
  });
  bot.quit('Testing client unavailability before Stone Vault activation');
  await end;
}

async function main() {
  let bot;
  let lock;
  let runId;
  try {
    if (!rcon('dungeon status').includes('No active Stone Vault activity.')) {
      throw new Error('Stone Vault must be idle before preactivation E2E');
    }
    bot = createBot();
    await awaitSpawn(bot);
    lock = startActivityLock();
    await waitFor('PostgreSQL V9 activity attach lock', () =>
      lock.stdout.includes('E2E_ATTACH_LOCKED'), 15_000);
    if (lock.child.exitCode !== null) {
      throw new Error(`Lock session ended early: ${lock.stderr}`);
    }
    console.log('E2E_VAULT_PREACTIVATION_LOCK_ACQUIRED');

    bot.chat('/dungeon start');
    runId = await waitFor('durable ACTIVE encounter before activity attachment', () =>
      sql(`SELECT e.run_id FROM encounter_run e JOIN encounter_participant p ON p.run_id = e.run_id JOIN player_identity i ON i.player_id = p.player_id WHERE e.status = 'ACTIVE' AND i.last_known_name = '${PLAYER}' ORDER BY e.started_at DESC LIMIT 1`) || null, 25_000);
    console.log(`E2E_VAULT_PREACTIVATION_DURABLE_START run=${runId}`);

    if (!rcon('dungeon status').includes('No active Stone Vault activity.')) {
      throw new Error('Activity spawned before V9 attach transaction completed');
    }
    await quitBot(bot);
    bot = null;
    // Mineflayer's socket close happens before Paper necessarily removes the
    // player from Bukkit's online registry. Verify server-side logout while
    // V9 attach is still blocked, or finishStart may see a stale online player
    // and legitimately activate the wave before its 200-tick wipe timeout.
    const onlineList = await waitFor('Paper-side disconnect before V9 attach', () => {
      const list = rcon('list').replace(/\\u001b\\[[0-9;]*m/g, '');
      if (!/players online:/i.test(list)) {
        throw new Error(`Unexpected Paper player-list response: ${list}`);
      }
      return list.includes(PLAYER) ? null : list;
    }, 15_000);
    console.log(`E2E_VAULT_PREACTIVATION_SERVER_LOGOUT_CONFIRMED list=${onlineList}`);
    // Allow at least a Paper tick after the disconnect observation before
    // asynchronous V9 attach is unblocked.
    await sleep(150);
    console.log('E2E_VAULT_PREACTIVATION_CLIENT_DISCONNECTED_BEFORE_ATTACH');
    await lock.release();
    lock = null;

    const failed = await waitFor('durable fail-closed preactivation result', () => {
      const value = sql(`SELECT status || '|' || coalesce(terminal_reason, '') FROM encounter_run WHERE run_id = '${runId}'::uuid`);
      return value === 'FAILED|START_PARTICIPANT_UNAVAILABLE' ? value : null;
    }, 45_000);
    const counts = sql(`SELECT (SELECT count(*) FROM structured_activity_run WHERE run_id = '${runId}'::uuid), (SELECT count(*) FROM encounter_participant WHERE run_id = '${runId}'::uuid), (SELECT count(*) FROM encounter_reward WHERE run_id = '${runId}'::uuid), (SELECT count(*) FROM encounter_reward_item i JOIN encounter_reward r ON r.reward_id=i.reward_id WHERE r.run_id = '${runId}'::uuid), (SELECT count(*) FROM audit_event WHERE idempotency_key='encounter:run:${runId}:terminal' AND event_type='encounter.run_failed')`);
    if (counts !== '1|1|0|0|1') {
      throw new Error(`Incorrect V9 attachment/participant/reward/audit state: ${counts}`);
    }
    if (!rcon('dungeon status').includes('No active Stone Vault activity.')) {
      throw new Error('Preactivation fail-closed path unexpectedly created a runtime');
    }
    if (!rcon('boss status').includes('No active Stone Warden encounter.')) {
      throw new Error('Preactivation failure unexpectedly activated Stone Warden');
    }
    console.log(`STONE_VAULT_PREACTIVATION_DISCONNECT_E2E_PASS run=${runId} terminal=${failed} activity/participants/rewards/items/audit=${counts}`);
  } catch (error) {
    console.error('STONE_VAULT_PREACTIVATION_DISCONNECT_E2E_DIAGNOSTIC', JSON.stringify({
      runId, error: String(error.stack || error),
      lockStdout: lock?.stdout, lockStderr: lock?.stderr,
      durable: (() => { try { return runId ? sql(`SELECT status || '|' || coalesce(terminal_reason, '') FROM encounter_run WHERE run_id = '${runId}'::uuid`) : null; } catch (e) { return String(e); } })(),
      playerList: (() => { try { return rcon('list'); } catch (e) { return String(e); } })(),
      dungeon: (() => { try { return rcon('dungeon status'); } catch (e) { return String(e); } })()
    }));
    throw error;
  } finally {
    await quitBot(bot);
    if (lock) await lock.release();
    await sleep(5_500); // Keep Paper's IP-level connection throttle enabled.
  }
}

main().then(() => process.exit(0)).catch(error => {
  console.error('STONE_VAULT_PREACTIVATION_DISCONNECT_E2E_FAIL');
  console.error(error.stack || error);
  process.exit(1);
});
