package dev.thedusk.minecraftserver.boss;

import dev.thedusk.minecraftserver.encounter.EncounterDefinition;
import dev.thedusk.minecraftserver.encounter.EncounterDefinitionRegistry;
import dev.thedusk.minecraftserver.encounter.EncounterParticipantReservationRegistry;
import dev.thedusk.minecraftserver.encounter.EncounterRun;
import dev.thedusk.minecraftserver.encounter.EncounterRunRepository;
import dev.thedusk.minecraftserver.encounter.EncounterRunResult;
import dev.thedusk.minecraftserver.encounter.EncounterRunStatus;
import dev.thedusk.minecraftserver.identity.PlayerIdentityRepository;
import dev.thedusk.minecraftserver.persistence.PersistenceExecutor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

public final class StoneWardenBossManager implements Listener, AutoCloseable {
    public static final String ENCOUNTER_ID = "stone_warden";

    private static final int NO_PARTICIPANT_GRACE_TICKS = 200;
    private static final int FIRST_MECHANIC_TICK = 80;
    private static final int NORMAL_SLAM_COOLDOWN = 180;
    private static final int ENRAGED_SLAM_COOLDOWN = 120;
    private static final int NORMAL_CHARGE_COOLDOWN = 240;
    private static final int ENRAGED_CHARGE_COOLDOWN = 160;
    private static final int SLAM_TELEGRAPH_TICKS = 30;
    private static final int CHARGE_TELEGRAPH_TICKS = 25;

    private final JavaPlugin plugin;
    private final PlayerIdentityRepository identities;
    private final EncounterDefinitionRegistry definitions;
    private final EncounterRunRepository encounterRuns;
    private final EncounterParticipantReservationRegistry reservations;
    private final PersistenceExecutor persistenceExecutor;
    private final Logger logger;
    private final NamespacedKey runKey;

    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, UUID> bossEntityToRun = new HashMap<>();
    private final Map<UUID, UUID> playerToRun = new HashMap<>();

    public StoneWardenBossManager(
            JavaPlugin plugin,
            PlayerIdentityRepository identities,
            EncounterDefinitionRegistry definitions,
            EncounterRunRepository encounterRuns,
            EncounterParticipantReservationRegistry reservations,
            PersistenceExecutor persistenceExecutor,
            Logger logger
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.encounterRuns = Objects.requireNonNull(encounterRuns, "encounterRuns");
        this.reservations = Objects.requireNonNull(reservations, "reservations");
        this.persistenceExecutor = Objects.requireNonNull(persistenceExecutor, "persistenceExecutor");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.runKey = new NamespacedKey(plugin, "stone_warden_run");
    }

    public void start(Player leader, List<Player> requestedParticipants) {
        requirePrimaryThread();
        Objects.requireNonNull(leader, "leader");
        Objects.requireNonNull(requestedParticipants, "requestedParticipants");

        EncounterDefinition definition = definitions.require(ENCOUNTER_ID);
        List<Player> participants = normalizeParticipants(leader, requestedParticipants, definition);
        List<UUID> participantUuids = participants.stream().map(Player::getUniqueId).toList();
        Optional<UUID> conflict = reservations.tryReserve(participantUuids);
        if (conflict.isPresent()) {
            String name = participants.stream()
                    .filter(player -> player.getUniqueId().equals(conflict.orElseThrow()))
                    .map(Player::getName)
                    .findFirst()
                    .orElse(conflict.orElseThrow().toString());
            leader.sendMessage("§c" + name + " đang ở trong một encounter khác.");
            return;
        }

        List<ParticipantSeed> seeds = participants.stream()
                .map(player -> new ParticipantSeed(player.getUniqueId(), player.getName()))
                .toList();
        UUID startCorrelationId = UUID.randomUUID();

        leader.sendMessage("§7Đang tạo Stone Warden encounter...");
        persistenceExecutor.submit(() -> {
                    List<UUID> internalPlayerIds = new ArrayList<>(seeds.size());
                    for (ParticipantSeed seed : seeds) {
                        internalPlayerIds.add(
                                identities.recordSeen(seed.minecraftUuid(), seed.playerName()).playerId()
                        );
                    }
                    return encounterRuns.start(definition, internalPlayerIds, startCorrelationId);
                })
                .whenComplete((result, failure) -> {
                    if (!plugin.isEnabled()) {
                        return;
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> finishStart(
                            leader.getUniqueId(), seeds, definition, result, failure
                    ));
                });
    }

    /**
     * Activates Stone Warden combat on an already-created durable encounter run.
     * No encounter row or participant row is created here; the supplied run remains authority.
     */
    public boolean activateExistingRun(
            EncounterRun run,
            List<UUID> canonicalParticipantUuids,
            Location arenaCenter,
            TerminalListener terminalListener
    ) {
        requirePrimaryThread();
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(canonicalParticipantUuids, "canonicalParticipantUuids");
        Objects.requireNonNull(arenaCenter, "arenaCenter");
        Objects.requireNonNull(terminalListener, "terminalListener");

        EncounterDefinition definition = definitions.require(ENCOUNTER_ID);
        List<UUID> participants = normalizeCanonicalParticipants(canonicalParticipantUuids, definition);
        if (!run.encounterId().equals(definition.id())
                || run.encounterVersion() != definition.version()
                || !run.definitionSha256().equals(definition.definitionSha256())
                || run.status() != EncounterRunStatus.ACTIVE
                || run.participantPlayerIds().size() != participants.size()) {
            logger.warning("Rejected Stone Warden existing-run activation due to authority mismatch: "
                    + run.runId());
            return false;
        }
        if (!reservations.areAllReserved(participants)) {
            logger.warning("Rejected Stone Warden existing-run activation without participant reservation: "
                    + run.runId());
            return false;
        }
        if (sessions.containsKey(run.runId())
                || participants.stream().anyMatch(playerToRun::containsKey)) {
            logger.warning("Rejected duplicate Stone Warden runtime activation for run " + run.runId());
            return false;
        }

        World world = arenaCenter.getWorld();
        if (world == null) {
            return false;
        }
        List<Player> availableParticipants = participants.stream()
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull)
                .filter(Player::isOnline)
                .filter(player -> !player.isDead())
                .filter(player -> player.getWorld() == world)
                .toList();
        if (availableParticipants.isEmpty()) {
            logger.warning("Rejected Stone Warden existing-run activation with no available participants: "
                    + run.runId());
            return false;
        }

        return spawnSession(
                run,
                definition,
                participants,
                availableParticipants,
                arenaCenter,
                terminalListener
        );
    }

    public String status() {
        requirePrimaryThread();
        if (sessions.isEmpty()) {
            return "No active Stone Warden encounter.";
        }
        return "Active Stone Warden encounters: " + sessions.size();
    }

    public boolean isParticipant(UUID minecraftUuid) {
        requirePrimaryThread();
        return reservations.isReserved(minecraftUuid);
    }

    private void finishStart(
            UUID leaderUuid,
            List<ParticipantSeed> seeds,
            EncounterDefinition definition,
            EncounterRunResult result,
            Throwable failure
    ) {
        requirePrimaryThread();
        List<UUID> participantUuids = seeds.stream().map(ParticipantSeed::minecraftUuid).toList();
        Player leader = Bukkit.getPlayer(leaderUuid);
        if (failure != null) {
            reservations.release(participantUuids);
            logger.log(Level.SEVERE, "Failed to persist Stone Warden encounter start", failure);
            if (leader != null) {
                leader.sendMessage("§cKhông thể tạo boss encounter. Không có boss nào được spawn.");
            }
            return;
        }
        if (result == null) {
            reservations.release(participantUuids);
            logger.severe("Stone Warden start completed without a result");
            return;
        }

        List<Player> onlineParticipants = new ArrayList<>(seeds.size());
        for (ParticipantSeed seed : seeds) {
            Player player = Bukkit.getPlayer(seed.minecraftUuid());
            if (player == null || !player.isOnline() || player.isDead()) {
                reservations.release(participantUuids);
                failPersistedStart(result.run().runId(), "START_PARTICIPANT_UNAVAILABLE");
                if (leader != null) {
                    leader.sendMessage("§cEncounter bị hủy vì một thành viên không còn sẵn sàng.");
                }
                return;
            }
            if (playerToRun.containsKey(player.getUniqueId())) {
                reservations.release(participantUuids);
                failPersistedStart(result.run().runId(), "START_PARTICIPANT_CONFLICT");
                return;
            }
            onlineParticipants.add(player);
        }

        World world = onlineParticipants.getFirst().getWorld();
        if (onlineParticipants.stream().anyMatch(player -> player.getWorld() != world)) {
            reservations.release(participantUuids);
            failPersistedStart(result.run().runId(), "START_PARTICIPANT_WORLD_MISMATCH");
            return;
        }

        Location arenaCenter = onlineParticipants.getFirst().getLocation().clone();
        boolean spawned = spawnSession(
                result.run(),
                definition,
                participantUuids,
                onlineParticipants,
                arenaCenter,
                null
        );
        if (!spawned) {
            reservations.release(participantUuids);
            failPersistedStart(result.run().runId(), "BOSS_SPAWN_FAILED");
        }
    }

    private boolean spawnSession(
            EncounterRun run,
            EncounterDefinition definition,
            List<UUID> canonicalParticipants,
            List<Player> availableParticipants,
            Location arenaCenter,
            TerminalListener terminalListener
    ) {
        requirePrimaryThread();
        Location bossSpawn = arenaCenter.clone().add(4.0, 0.0, 0.0);
        if (!bossSpawn.getBlock().isPassable()) {
            bossSpawn = arenaCenter.clone().add(0.0, 1.0, 0.0);
        }

        IronGolem boss = null;
        try {
            boss = arenaCenter.getWorld().spawn(bossSpawn, IronGolem.class);
            boss.setPlayerCreated(false);
            boss.setRemoveWhenFarAway(false);
            boss.setPersistent(false);
            boss.customName(Component.text("Stone Warden"));
            boss.setCustomNameVisible(true);

            double maxHealth = StoneWardenCombatRules.maxHealth(canonicalParticipants.size());
            setAttribute(boss, Attribute.MAX_HEALTH, maxHealth);
            setAttribute(boss, Attribute.ATTACK_DAMAGE, 8.0 + canonicalParticipants.size());
            setAttribute(boss, Attribute.MOVEMENT_SPEED, 0.27);
            boss.setHealth(maxHealth);
            boss.getPersistentDataContainer().set(
                    runKey,
                    PersistentDataType.STRING,
                    run.runId().toString()
            );
        } catch (RuntimeException exception) {
            if (boss != null && !boss.isDead()) {
                boss.remove();
            }
            logger.log(Level.SEVERE, "Failed to spawn Stone Warden for run " + run.runId(), exception);
            return false;
        }

        BossBar bossBar = Bukkit.createBossBar("Stone Warden", BarColor.WHITE, BarStyle.SEGMENTED_10);
        availableParticipants.forEach(bossBar::addPlayer);

        Session session = new Session(
                run.runId(),
                definition,
                boss.getUniqueId(),
                canonicalParticipants,
                arenaCenter,
                bossBar,
                terminalListener
        );
        sessions.put(run.runId(), session);
        bossEntityToRun.put(boss.getUniqueId(), run.runId());
        session.participants().forEach(uuid -> playerToRun.put(uuid, run.runId()));

        session.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(session), 1L, 1L);
        for (Player player : availableParticipants) {
            player.sendMessage("§6Stone Warden đã thức tỉnh. §7Giữ trong bán kính arena và để ý telegraph.");
        }
        logger.info("BOSS_TELEMETRY event=started run=" + run.runId()
                + " boss=stone_warden participants=" + canonicalParticipants.size());
        return true;
    }

    private void tick(Session session) {
        requirePrimaryThread();
        if (!isActive(session)) {
            return;
        }
        session.ticks++;

        IronGolem boss = boss(session);
        if (boss == null || boss.isDead()) {
            fail(session, "BOSS_ENTITY_MISSING");
            return;
        }

        updateBossBar(session, boss);
        enforceBossLeash(session, boss);
        List<Player> eligible = eligibleParticipants(session);
        if (eligible.isEmpty()) {
            session.noParticipantTicks++;
            if (session.noParticipantTicks >= NO_PARTICIPANT_GRACE_TICKS) {
                fail(session, "NO_ACTIVE_PARTICIPANTS");
            }
            return;
        }
        session.noParticipantTicks = 0;
        boss.setTarget(nearestPlayer(boss.getLocation(), eligible));

        if (session.ticks >= session.definition().timeoutSeconds() * 20L) {
            fail(session, "TIMEOUT");
            return;
        }

        double maxHealth = maxHealth(boss);
        if (!session.enraged && maxHealth > 0.0
                && boss.getHealth() / maxHealth <= StoneWardenCombatRules.ENRAGE_HEALTH_FRACTION) {
            session.enraged = true;
            boss.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 60 * 10, 0, false, false));
            broadcast(session, "§cStone Warden enrages — mechanics accelerate!");
            boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_IRON_GOLEM_DAMAGE, 1.0f, 0.6f);
            logger.info("BOSS_TELEMETRY event=enraged run=" + session.runId());
        }

        if (session.ticks < FIRST_MECHANIC_TICK || session.ticks < session.mechanicLockUntilTick) {
            return;
        }

        int slamCooldown = session.enraged ? ENRAGED_SLAM_COOLDOWN : NORMAL_SLAM_COOLDOWN;
        int chargeCooldown = session.enraged ? ENRAGED_CHARGE_COOLDOWN : NORMAL_CHARGE_COOLDOWN;
        if (session.ticks - session.lastSlamTick >= slamCooldown) {
            session.lastSlamTick = session.ticks;
            session.mechanicLockUntilTick = session.ticks + SLAM_TELEGRAPH_TICKS + 10L;
            telegraphSlam(session, boss);
            return;
        }
        if (session.ticks - session.lastChargeTick >= chargeCooldown) {
            session.lastChargeTick = session.ticks;
            session.mechanicLockUntilTick = session.ticks + CHARGE_TELEGRAPH_TICKS + 10L;
            telegraphCharge(session, boss, nearestPlayer(boss.getLocation(), eligible));
        }
    }

    private void telegraphSlam(Session session, IronGolem boss) {
        session.slamCount++;
        broadcast(session, "§eTelegraph: §cSeismic Slam §7— rời khỏi vùng gần boss!");
        World world = boss.getWorld();
        world.playSound(boss.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.9f, 0.6f);
        world.spawnParticle(
                Particle.CLOUD,
                boss.getLocation().clone().add(0, 0.2, 0),
                70,
                StoneWardenCombatRules.SLAM_RADIUS,
                0.2,
                StoneWardenCombatRules.SLAM_RADIUS,
                0.01
        );
        logger.info("BOSS_TELEMETRY event=slam_telegraph run=" + session.runId()
                + " count=" + session.slamCount);
        Bukkit.getScheduler().runTaskLater(plugin, () -> executeSlam(session.runId()), SLAM_TELEGRAPH_TICKS);
    }

    private void executeSlam(UUID runId) {
        Session session = sessions.get(runId);
        if (session == null || session.terminalRequested) {
            return;
        }
        IronGolem boss = boss(session);
        if (boss == null || boss.isDead()) {
            return;
        }
        Location bossLocation = boss.getLocation();
        boss.getWorld().spawnParticle(Particle.EXPLOSION, bossLocation, 8, 1.3, 0.2, 1.3, 0.0);
        boss.getWorld().playSound(bossLocation, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.2f, 0.65f);
        for (Player player : onlineParticipants(session)) {
            if (player.getWorld() != boss.getWorld()) {
                continue;
            }
            if (player.getLocation().distanceSquared(bossLocation)
                    > StoneWardenCombatRules.SLAM_RADIUS * StoneWardenCombatRules.SLAM_RADIUS) {
                continue;
            }
            player.damage(session.enraged ? 8.0 : 6.0, boss);
            Vector knockback = player.getLocation().toVector().subtract(bossLocation.toVector());
            knockback.setY(0.0);
            if (knockback.lengthSquared() > 0.001) {
                knockback.normalize().multiply(0.85);
            }
            knockback.setY(0.35);
            player.setVelocity(knockback);
        }
    }

    private void telegraphCharge(Session session, IronGolem boss, Player target) {
        session.chargeCount++;
        Location start = boss.getLocation().clone();
        Vector direction = target.getLocation().toVector().subtract(start.toVector());
        direction.setY(0.0);
        if (direction.lengthSquared() < 0.01) {
            return;
        }
        direction.normalize();
        double length = Math.min(12.0, Math.max(6.0, horizontalDistance(start, target.getLocation())));
        Location end = start.clone().add(direction.clone().multiply(length));

        broadcast(session, "§eTelegraph: §6Stone Charge §7— tránh khỏi đường thẳng đánh dấu!");
        drawChargeLine(start, direction, length);
        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_IRON_GOLEM_ATTACK, 0.8f, 0.8f);
        logger.info("BOSS_TELEMETRY event=charge_telegraph run=" + session.runId()
                + " count=" + session.chargeCount);

        ChargePath path = new ChargePath(start.getWorld().getUID(), start.getX(), start.getZ(), end.getX(), end.getZ());
        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> executeCharge(session.runId(), path, direction),
                CHARGE_TELEGRAPH_TICKS
        );
    }

    private void executeCharge(UUID runId, ChargePath path, Vector direction) {
        Session session = sessions.get(runId);
        if (session == null || session.terminalRequested) {
            return;
        }
        IronGolem boss = boss(session);
        if (boss == null || boss.isDead() || !boss.getWorld().getUID().equals(path.worldId())) {
            return;
        }

        boss.setVelocity(direction.clone().multiply(session.enraged ? 1.55 : 1.3).setY(0.12));
        double widthSquared = StoneWardenCombatRules.CHARGE_WIDTH * StoneWardenCombatRules.CHARGE_WIDTH;
        for (Player player : onlineParticipants(session)) {
            if (!player.getWorld().getUID().equals(path.worldId()) || !insideArena(session, player.getLocation())) {
                continue;
            }
            double distanceSquared = StoneWardenCombatRules.distanceSquaredToSegment2d(
                    player.getLocation().getX(),
                    player.getLocation().getZ(),
                    path.startX(),
                    path.startZ(),
                    path.endX(),
                    path.endZ()
            );
            if (distanceSquared <= widthSquared) {
                player.damage(session.enraged ? 9.0 : 7.0, boss);
            }
        }
    }

    private void drawChargeLine(Location start, Vector direction, double length) {
        for (double distance = 0.0; distance <= length; distance += 0.65) {
            Location point = start.clone().add(direction.clone().multiply(distance)).add(0, 0.2, 0);
            start.getWorld().spawnParticle(Particle.CRIT, point, 2, 0.08, 0.05, 0.08, 0.0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBossDamage(EntityDamageEvent event) {
        UUID runId = bossEntityToRun.get(event.getEntity().getUniqueId());
        if (runId == null) {
            return;
        }
        Session session = sessions.get(runId);
        if (session == null || session.terminalRequested) {
            event.setCancelled(true);
            return;
        }

        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            event.setCancelled(true);
            return;
        }

        Player attacker = playerAttacker(byEntity.getDamager());
        if (attacker == null
                || !session.participants().contains(attacker.getUniqueId())
                || !insideArena(session, attacker.getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBossTarget(EntityTargetLivingEntityEvent event) {
        UUID runId = bossEntityToRun.get(event.getEntity().getUniqueId());
        if (runId == null) {
            return;
        }
        Session session = sessions.get(runId);
        if (session == null || !(event.getTarget() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }
        if (!session.participants().contains(player.getUniqueId()) || !insideArena(session, player.getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBossDeath(EntityDeathEvent event) {
        UUID runId = bossEntityToRun.get(event.getEntity().getUniqueId());
        if (runId == null) {
            return;
        }
        Session session = sessions.get(runId);
        if (session == null || session.terminalRequested) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        complete(session);
    }

    private Player playerAttacker(Entity entity) {
        if (entity instanceof Player player) {
            return player;
        }
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    private void complete(Session session) {
        terminal(session, true, null);
    }

    private void fail(Session session, String reason) {
        terminal(session, false, reason);
    }

    private void terminal(Session session, boolean completed, String reason) {
        requirePrimaryThread();
        if (!isActive(session) || session.terminalRequested) {
            return;
        }
        session.terminalRequested = true;
        cleanupLocal(session, completed);
        UUID correlationId = UUID.randomUUID();
        long durationTicks = session.ticks;
        persistenceExecutor.submit(() -> completed
                        ? encounterRuns.complete(session.runId(), correlationId)
                        : encounterRuns.fail(session.runId(), correlationId, reason))
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        logger.log(
                                Level.SEVERE,
                                "Failed to persist Stone Warden terminal state for run " + session.runId(),
                                failure
                        );
                        return;
                    }
                    logger.info("BOSS_TELEMETRY event=" + (completed ? "completed" : "failed")
                            + " run=" + session.runId()
                            + " durationTicks=" + durationTicks
                            + " slams=" + session.slamCount
                            + " charges=" + session.chargeCount
                            + (reason == null ? "" : " reason=" + reason));
                    TerminalListener terminalListener = session.terminalListener();
                    if (terminalListener != null && plugin.isEnabled()) {
                        Bukkit.getScheduler().runTask(
                                plugin,
                                () -> terminalListener.onTerminal(session.runId(), completed, reason)
                        );
                    }
                });
    }

    private void failPersistedStart(UUID runId, String reason) {
        UUID correlationId = UUID.randomUUID();
        persistenceExecutor.submit(() -> encounterRuns.fail(runId, correlationId, reason))
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        logger.log(Level.SEVERE, "Failed to mark unspawned encounter failed: " + runId, failure);
                    }
                });
    }

    private void cleanupLocal(Session session, boolean bossAlreadyDead) {
        sessions.remove(session.runId());
        bossEntityToRun.remove(session.bossEntityId());
        session.participants().forEach(uuid -> playerToRun.remove(uuid, session.runId()));
        reservations.release(session.participants());
        if (session.task != null) {
            session.task.cancel();
        }
        session.bossBar().removeAll();

        if (!bossAlreadyDead) {
            Entity entity = Bukkit.getEntity(session.bossEntityId());
            if (entity != null && !entity.isDead()) {
                entity.remove();
            }
        }
        broadcast(session, bossAlreadyDead
                ? "§aStone Warden đã bị đánh bại. §7Reward sẽ được thêm ở Phase 4C."
                : "§cStone Warden encounter thất bại và đã reset.");
    }

    private void updateBossBar(Session session, IronGolem boss) {
        double maxHealth = maxHealth(boss);
        if (maxHealth > 0.0) {
            session.bossBar().setProgress(Math.max(0.0, Math.min(1.0, boss.getHealth() / maxHealth)));
        }
        for (Player player : onlineParticipants(session)) {
            session.bossBar().addPlayer(player);
        }
    }

    private void enforceBossLeash(Session session, IronGolem boss) {
        if (boss.getWorld() != session.arenaCenter().getWorld()
                || !StoneWardenCombatRules.insideArena(
                        session.arenaCenter().getX(),
                        session.arenaCenter().getY(),
                        session.arenaCenter().getZ(),
                        boss.getLocation().getX(),
                        boss.getLocation().getY(),
                        boss.getLocation().getZ(),
                        StoneWardenCombatRules.ARENA_RADIUS,
                        StoneWardenCombatRules.VERTICAL_TOLERANCE
                )) {
            boss.teleport(session.arenaCenter());
            boss.setVelocity(new Vector());
        }
    }

    private boolean insideArena(Session session, Location location) {
        return location.getWorld() == session.arenaCenter().getWorld()
                && StoneWardenCombatRules.insideArena(
                session.arenaCenter().getX(),
                session.arenaCenter().getY(),
                session.arenaCenter().getZ(),
                location.getX(),
                location.getY(),
                location.getZ(),
                StoneWardenCombatRules.ARENA_RADIUS,
                StoneWardenCombatRules.VERTICAL_TOLERANCE
        );
    }

    private List<Player> eligibleParticipants(Session session) {
        return onlineParticipants(session).stream()
                .filter(player -> !player.isDead())
                .filter(player -> player.getWorld() == session.arenaCenter().getWorld())
                .filter(player -> StoneWardenCombatRules.insideArena(
                        session.arenaCenter().getX(),
                        session.arenaCenter().getY(),
                        session.arenaCenter().getZ(),
                        player.getLocation().getX(),
                        player.getLocation().getY(),
                        player.getLocation().getZ(),
                        StoneWardenCombatRules.PARTICIPANT_RADIUS,
                        StoneWardenCombatRules.VERTICAL_TOLERANCE
                ))
                .toList();
    }

    private List<Player> onlineParticipants(Session session) {
        return session.participants().stream()
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull)
                .filter(Player::isOnline)
                .toList();
    }

    private void broadcast(Session session, String message) {
        for (Player player : onlineParticipants(session)) {
            player.sendMessage(message);
        }
    }

    private IronGolem boss(Session session) {
        Entity entity = Bukkit.getEntity(session.bossEntityId());
        return entity instanceof IronGolem ironGolem ? ironGolem : null;
    }

    private boolean isActive(Session session) {
        return sessions.get(session.runId()) == session;
    }

    private static Player nearestPlayer(Location origin, List<Player> players) {
        return players.stream()
                .min(Comparator.comparingDouble(player -> player.getLocation().distanceSquared(origin)))
                .orElseThrow();
    }

    private static double horizontalDistance(Location first, Location second) {
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private List<Player> normalizeParticipants(
            Player leader,
            List<Player> requestedParticipants,
            EncounterDefinition definition
    ) {
        if (!leader.isOnline() || leader.isDead()) {
            throw new IllegalArgumentException("Leader must be online and alive");
        }
        Map<UUID, Player> unique = new HashMap<>();
        unique.put(leader.getUniqueId(), leader);
        for (Player player : requestedParticipants) {
            Objects.requireNonNull(player, "participant");
            if (!player.isOnline() || player.isDead()) {
                throw new IllegalArgumentException(player.getName() + " must be online and alive");
            }
            if (player.getWorld() != leader.getWorld()) {
                throw new IllegalArgumentException(player.getName() + " must be in the same world");
            }
            if (player.getLocation().distanceSquared(leader.getLocation()) > 12.0 * 12.0) {
                throw new IllegalArgumentException(player.getName() + " must be within 12 blocks of the leader");
            }
            unique.put(player.getUniqueId(), player);
        }
        List<Player> normalized = new ArrayList<>(unique.values());
        normalized.sort(Comparator.comparing(player -> player.getUniqueId().toString()));
        normalized.remove(leader);
        normalized.addFirst(leader);
        if (normalized.size() < definition.minPlayers() || normalized.size() > definition.maxPlayers()) {
            throw new IllegalArgumentException(
                    "Stone Warden requires " + definition.minPlayers() + "-" + definition.maxPlayers() + " players"
            );
        }
        return List.copyOf(normalized);
    }

    private static List<UUID> normalizeCanonicalParticipants(
            List<UUID> canonicalParticipantUuids,
            EncounterDefinition definition
    ) {
        LinkedHashSet<UUID> unique = new LinkedHashSet<>();
        for (UUID participantUuid : canonicalParticipantUuids) {
            unique.add(Objects.requireNonNull(participantUuid, "participantUuid"));
        }
        if (unique.size() != canonicalParticipantUuids.size()) {
            throw new IllegalArgumentException("canonicalParticipantUuids must not contain duplicates");
        }
        if (unique.size() < definition.minPlayers() || unique.size() > definition.maxPlayers()) {
            throw new IllegalArgumentException(
                    "Stone Warden requires " + definition.minPlayers() + "-" + definition.maxPlayers() + " players"
            );
        }
        return List.copyOf(unique);
    }

    private static void setAttribute(IronGolem boss, Attribute attribute, double value) {
        AttributeInstance instance = boss.getAttribute(attribute);
        if (instance == null) {
            throw new IllegalStateException("Missing boss attribute " + attribute);
        }
        instance.setBaseValue(value);
    }

    private static double maxHealth(IronGolem boss) {
        AttributeInstance instance = boss.getAttribute(Attribute.MAX_HEALTH);
        return instance == null ? 0.0 : instance.getValue();
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Stone Warden runtime must be accessed from the Bukkit main thread");
        }
    }

    @Override
    public void close() {
        requirePrimaryThread();
        for (Session session : List.copyOf(sessions.values())) {
            session.terminalRequested = true;
            cleanupLocal(session, false);
        }
    }

    @FunctionalInterface
    public interface TerminalListener {
        void onTerminal(UUID runId, boolean completed, String reason);
    }

    private record ParticipantSeed(UUID minecraftUuid, String playerName) {
    }

    private record ChargePath(UUID worldId, double startX, double startZ, double endX, double endZ) {
    }

    private static final class Session {
        private final UUID runId;
        private final EncounterDefinition definition;
        private final UUID bossEntityId;
        private final List<UUID> participants;
        private final Location arenaCenter;
        private final BossBar bossBar;
        private final TerminalListener terminalListener;

        private BukkitTask task;
        private long ticks;
        private long lastSlamTick = -NORMAL_SLAM_COOLDOWN;
        private long lastChargeTick = FIRST_MECHANIC_TICK - NORMAL_CHARGE_COOLDOWN;
        private long mechanicLockUntilTick;
        private int noParticipantTicks;
        private int slamCount;
        private int chargeCount;
        private boolean enraged;
        private boolean terminalRequested;

        private Session(
                UUID runId,
                EncounterDefinition definition,
                UUID bossEntityId,
                List<UUID> participants,
                Location arenaCenter,
                BossBar bossBar,
                TerminalListener terminalListener
        ) {
            this.runId = runId;
            this.definition = definition;
            this.bossEntityId = bossEntityId;
            this.participants = List.copyOf(participants);
            this.arenaCenter = arenaCenter.clone();
            this.bossBar = bossBar;
            this.terminalListener = terminalListener;
        }

        private UUID runId() {
            return runId;
        }

        private EncounterDefinition definition() {
            return definition;
        }

        private UUID bossEntityId() {
            return bossEntityId;
        }

        private List<UUID> participants() {
            return participants;
        }

        private Location arenaCenter() {
            return arenaCenter;
        }

        private BossBar bossBar() {
            return bossBar;
        }

        private TerminalListener terminalListener() {
            return terminalListener;
        }
    }
}
