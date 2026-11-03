'use strict';

const { execFileSync } = require('node:child_process');
const { appendFileSync } = require('node:fs');
const mineflayer = require('mineflayer');
const { pathfinder, Movements, goals: { GoalNear } } = require('mineflayer-pathfinder');

const LEADER_NAME = 'StoneWardenLead';
const MATE_NAME = 'StoneWardenMate';
const BOT_HOST = process.env.E2E_BOT_HOST || '127.0.0.1';
const BOT_PORT = Number(process.env.E2E_BOT_PORT || '25568');
const BOT_VERSION = process.env.E2E_BOT_VERSION || '26.1';
const COMPOSE_FILE = process.env.E2E_COMPOSE_FILE || 'compose.e2e.yaml';
const PAPER_SERVICE = process.env.E2E_PAPER_SERVICE || 'paper-e2e';
const BOSS_MELEE_DISTANCE = 2.75;
const BOSS_HURT_CONFIRM_TIMEOUT_MS = 1_800;
const PARTY_CONVERGENCE_DISTANCE = 9.0;
const PARTY_CONVERGENCE_VERTICAL_DELTA = 2.0;
const PARTY_PATHFIND_GOAL_RADIUS = 2;
const CONNECTION_THROTTLE_CLEAR_MS = 5_500;

const diagnostic = {
  phase: 'startup',
  leader: null,
  mate: null,
  bossId: null,
  leaderAttackAttempts: 0,
  leaderConfirmedHits: 0,
  mateAttackAttempts: 0,
  mateConfirmedHits: 0,
  lastAttackContext: null,
  lastAttackDistance: null,
  lastAttackResult: null
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
    return `unavailable: ${error.message || error}`;
  }
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

function hasStoneWardenShard(tracked) {
  return tracked.bot.inventory.items().some((item) => item.name === 'amethyst_shard');
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

function formatPosition(tracked) {
  const position = tracked?.bot?.entity?.position;
  if (!position) {
    return 'unavailable';
  }
  return `${position.x.toFixed(2)},${position.y.toFixed(2)},${position.z.toFixed(2)}`;
}

function formatBossObservation(tracked) {
  if (!tracked?.bot || diagnostic.bossId == null) {
    return 'unavailable';
  }
  const boss = tracked.bot.entities?.[diagnostic.bossId];
  const playerPosition = tracked.bot.entity?.position;
  if (!boss?.position || !playerPosition) {
    return 'not-visible';
  }
  return `id=${boss.id} position=${boss.position.x.toFixed(2)},${boss.position.y.toFixed(2)},${boss.position.z.toFixed(2)} distance=${playerPosition.distanceTo(boss.position).toFixed(2)}`;
}

function formatClientVitals(tracked) {
  if (!tracked?.bot) {
    return 'unavailable';
  }
  const health = Number.isFinite(tracked.bot.health) ? tracked.bot.health.toFixed(1) : 'unavailable';
  const food = Number.isFinite(tracked.bot.food) ? tracked.bot.food.toFixed(1) : 'unavailable';
  return `health=${health} food=${food} connected=${Boolean(tracked.bot.entity)} kicked=${JSON.stringify(tracked.kicked?.() || null)}`;
}

function buildFailureDiagnosticLines(error) {
  const leader = diagnostic.leader;
  const mate = diagnostic.mate;
  let distance = 'unavailable';
  let verticalDelta = 'unavailable';
  if (leader?.bot?.entity?.position && mate?.bot?.entity?.position) {
    distance = partyDistance(leader.bot.entity.position, mate.bot.entity.position).toFixed(2);
    verticalDelta = Math.abs(
      leader.bot.entity.position.y - mate.bot.entity.position.y
    ).toFixed(2);
  }

  return [
    '### Stone Warden party E2E diagnostic',
    '',
    `- phase: \`${diagnostic.phase}\``,
    `- error: \`${String(error?.message || error).replace(/`/g, "'")}\``,
    `- leader position: \`${formatPosition(leader)}\``,
    `- mate position: \`${formatPosition(mate)}\``,
    `- party distance: \`${distance}\``,
    `- party vertical delta: \`${verticalDelta}\``,
    `- leader vitals: \`${formatClientVitals(leader).replace(/`/g, "'")}\``,
    `- mate vitals: \`${formatClientVitals(mate).replace(/`/g, "'")}\``,
    `- boss entity id: \`${diagnostic.bossId ?? 'unavailable'}\``,
    `- boss seen by leader: \`${formatBossObservation(leader)}\``,
    `- boss seen by mate: \`${formatBossObservation(mate)}\``,
    `- boss status: \`${safeRcon('boss status').replace(/`/g, "'")}\``,
    `- leader attacks: \`attempts=${diagnostic.leaderAttackAttempts} confirmed=${diagnostic.leaderConfirmedHits}\``,
    `- mate attacks: \`attempts=${diagnostic.mateAttackAttempts} confirmed=${diagnostic.mateConfirmedHits}\``,
    `- last attack: \`context=${diagnostic.lastAttackContext ?? 'unavailable'} distance=${diagnostic.lastAttackDistance ?? 'unavailable'} result=${diagnostic.lastAttackResult ?? 'unavailable'}\``,
    `- leader shard: \`${leader ? hasStoneWardenShard(leader) : false}\``,
    `- mate shard: \`${mate ? hasStoneWardenShard(mate) : false}\``,
    ''
  ];
}

function writeFailureSummary(error) {
  const lines = buildFailureDiagnosticLines(error);
  const diagnosticText = [
    'STONE_WARDEN_PARTY_E2E_DIAGNOSTIC_BEGIN',
    ...lines,
    'STONE_WARDEN_PARTY_E2E_DIAGNOSTIC_END',
    ''
  ].join('\n');

  console.error(diagnosticText.trimEnd());

  const runnerTemp = process.env.RUNNER_TEMP;
  if (runnerTemp) {
    try {
      appendFileSync(`${runnerTemp}/stone-vault-party.log`, diagnosticText, 'utf8');
    } catch (artifactError) {
      console.error('Failed to persist Stone Warden diagnostic artifact:', artifactError);
    }
  }

  const summaryPath = process.env.GITHUB_STEP_SUMMARY;
  if (!summaryPath) {
    return;
  }

  try {
    appendFileSync(summaryPath, `${lines.join('\n')}\n`, 'utf8');
  } catch (summaryError) {
    console.error('Failed to write GitHub step summary:', summaryError);
  }
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

async function walkIntoBossMeleeRange(tracked, bossId, timeoutMs) {
  const { bot } = tracked;
  const deadline = Date.now() + timeoutMs;
  let bestDistance = Number.POSITIVE_INFINITY;
  let lastProgressAt = Date.now();
  bot.setControlState('sprint', true);

  try {
    while (Date.now() < deadline) {
      assertConnected(tracked, 'while approaching Stone Warden');
      const target = bot.entities[bossId];
      if (!target) {
        return false;
      }

      const distance = bot.entity.position.distanceTo(target.position);
      if (distance <= BOSS_MELEE_DISTANCE) {
        return true;
      }
      if (distance + 0.1 < bestDistance) {
        bestDistance = distance;
        lastProgressAt = Date.now();
      }

      await bot.lookAt(target.position.offset(0, 1.0, 0), true);
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
  assertConnected(leader, 'before convergence');
  assertConnected(mate, 'before convergence');

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
      `Party convergence mover=${mover.username} target=${target.username} `
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

async function keepNearStart(tracked, start) {
  assertConnected(tracked, 'during party mechanic verification');
  const { bot } = tracked;
  const displacedHorizontally = horizontalDistance(bot.entity.position, start) > 10;
  const displacedVertically = Math.abs(bot.entity.position.y - start.y) > 5;
  if (displacedHorizontally || displacedVertically) {
    await walkToward(tracked, start, 5.5, 2_500);
  }
}

async function attackBossAndConfirmDamage(tracked, bossId, context) {
  assertConnected(tracked, context);
  const { bot } = tracked;
  const inReach = await walkIntoBossMeleeRange(tracked, bossId, 3_500);
  const target = bot.entities[bossId];
  if (!inReach || !target) {
    diagnostic.lastAttackContext = context;
    diagnostic.lastAttackDistance = 'unavailable';
    diagnostic.lastAttackResult = 'target-unreachable';
    return false;
  }

  const distance = bot.entity.position.distanceTo(target.position);
  diagnostic.lastAttackContext = context;
  diagnostic.lastAttackDistance = distance.toFixed(2);
  if (distance > BOSS_MELEE_DISTANCE) {
    diagnostic.lastAttackResult = 'out-of-range';
    return false;
  }

  const isLeader = tracked.username === LEADER_NAME;
  if (isLeader) {
    diagnostic.leaderAttackAttempts++;
  } else if (tracked.username === MATE_NAME) {
    diagnostic.mateAttackAttempts++;
  }

  await bot.lookAt(target.position.offset(0, 1.0, 0), true);
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
      resolve(value);
    };
    const onEntityHurt = (entity, source) => {
      if (!entity || entity.id !== bossId) {
        return;
      }
      if (source && source.id !== attackerId) {
        return;
      }
      finish(true);
    };
    bot.on('entityHurt', onEntityHurt);
    timeout = setTimeout(() => finish(false), BOSS_HURT_CONFIRM_TIMEOUT_MS);
  });

  bot.attack(target, true);
  const confirmed = await confirmedDamage;
  diagnostic.lastAttackResult = confirmed ? 'confirmed' : 'hurt-timeout';
  if (confirmed) {
    if (isLeader) {
      diagnostic.leaderConfirmedHits++;
    } else if (tracked.username === MATE_NAME) {
      diagnostic.mateConfirmedHits++;
    }
  }
  return confirmed;
}

async function main() {
  const leader = createTrackedBot(LEADER_NAME);
  diagnostic.leader = leader;
  let mate = null;

  try {
    diagnostic.phase = 'leader-spawn';
    await awaitSpawn(leader);
    console.log(`${LEADER_NAME} spawned; waiting for Paper connection throttle window to clear.`);
    await sleep(CONNECTION_THROTTLE_CLEAR_MS);

    diagnostic.phase = 'mate-spawn';
    mate = createTrackedBot(MATE_NAME);
    diagnostic.mate = mate;
    await awaitSpawn(mate);
    console.log(`Party bots spawned: ${LEADER_NAME}, ${MATE_NAME} via ${BOT_VERSION} -> ViaProxy -> Paper 26.3`);

    diagnostic.phase = 'party-convergence';
    const converged = await convergePartyClients(leader, mate);
    if (!converged) {
      const distance = partyDistance(leader.bot.entity.position, mate.bot.entity.position);
      const verticalDelta = Math.abs(leader.bot.entity.position.y - mate.bot.entity.position.y);
      throw new Error(
        `Party clients could not reach combat-ready convergence; `
        + `distance=${distance.toFixed(2)} verticalDelta=${verticalDelta.toFixed(2)}`
      );
    }
    assertConnected(leader, 'before party start');
    assertConnected(mate, 'before party start');
    console.log(
      `Party clients combat-ready; distance=${partyDistance(
        leader.bot.entity.position,
        mate.bot.entity.position
      ).toFixed(2)} verticalDelta=${Math.abs(
        leader.bot.entity.position.y - mate.bot.entity.position.y
      ).toFixed(2)} blocks.`
    );

    const leaderStart = leader.bot.entity.position.clone();
    const mateStart = mate.bot.entity.position.clone();
    const initialEntityIds = new Set(Object.keys(leader.bot.entities));

    for (const name of [LEADER_NAME, MATE_NAME]) {
      rcon(`effect give ${name} minecraft:resistance 120 4 true`);
      rcon(`effect give ${name} minecraft:regeneration 120 4 true`);
    }

    diagnostic.phase = 'boss-start';
    leader.bot.chat(`/boss start ${MATE_NAME}`);

    const boss = await waitFor('party Stone Warden iron golem entity', () => {
      return Object.values(leader.bot.entities).find((entity) => {
        return !initialEntityIds.has(String(entity.id))
          && isIronGolem(entity)
          && entity.position.distanceTo(leader.bot.entity.position) <= 32;
      });
    }, 25_000, 100);
    diagnostic.bossId = boss.id;

    await waitFor('party Stone Warden entity on mate client', () => {
      const mateBoss = mate.bot.entities[boss.id];
      return mateBoss && isIronGolem(mateBoss) ? mateBoss : null;
    }, 10_000, 100);

    console.log(`Party Stone Warden entity observed by both clients: id=${boss.id}`);

    const active = rcon('boss status');
    if (!active.includes('Active Stone Warden encounters: 1')) {
      throw new Error(`Party boss was not active after start: ${active}`);
    }

    diagnostic.phase = 'mechanic-window';
    const mechanicDeadline = Date.now() + 16_000;
    while (Date.now() < mechanicDeadline) {
      await keepNearStart(leader, leaderStart);
      await keepNearStart(mate, mateStart);
      await sleep(250);
    }

    assertConnected(leader, 'after party mechanic window');
    assertConnected(mate, 'after party mechanic window');
    console.log('Both party clients remained connected through the mechanic window.');

    diagnostic.phase = 'mate-contribution';
    rcon(`effect give ${MATE_NAME} minecraft:strength 60 5 true`);
    let mateHits = 0;
    const mateContributionDeadline = Date.now() + 12_000;
    while (Date.now() < mateContributionDeadline && mateHits < 2) {
      if (safeRcon('boss status').includes('No active Stone Warden encounter.')) {
        break;
      }
      if (await attackBossAndConfirmDamage(mate, boss.id, 'during mate contribution verification')) {
        mateHits++;
        console.log(`${MATE_NAME} server-confirmed boss hit ${mateHits}/2.`);
        await sleep(700);
      } else {
        await sleep(200);
      }
    }
    if (mateHits < 2) {
      throw new Error(`Mate could not make required server-confirmed boss hits; confirmedHits=${mateHits}`);
    }

    diagnostic.phase = 'two-client-kill';
    rcon(`effect give ${LEADER_NAME} minecraft:strength 60 20 true`);

    const killDeadline = Date.now() + 35_000;
    while (Date.now() < killDeadline) {
      assertConnected(leader, 'during party kill verification');
      assertConnected(mate, 'during party kill verification');
      const status = rcon('boss status');
      if (status.includes('No active Stone Warden encounter.')) {
        diagnostic.phase = 'reward-delivery';
        console.log('Party Stone Warden runtime reports no active encounter after client attacks.');
        await waitFor(
          'Stone Warden Shard in both real client inventories',
          () => hasStoneWardenShard(leader) && hasStoneWardenShard(mate),
          15_000,
          250
        );
        console.log('Stone Warden Shard observed in both party client inventories.');
        diagnostic.phase = 'complete';
        await sleep(500);
        return;
      }

      const mateHit = await attackBossAndConfirmDamage(mate, boss.id, 'during mate party kill verification');
      if (mateHit) {
        console.log(`${MATE_NAME} server-confirmed boss hit during party kill.`);
        await sleep(550);
      }

      const leaderHit = await attackBossAndConfirmDamage(leader, boss.id, 'during leader party kill verification');
      if (leaderHit) {
        console.log(`${LEADER_NAME} server-confirmed boss hit during party kill.`);
        await sleep(550);
      } else if (!mateHit) {
        await sleep(200);
      }
    }

    throw new Error(`Party Stone Warden was not completed within kill deadline; status=${rcon('boss status')}`);
  } catch (error) {
    writeFailureSummary(error);
    throw error;
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
