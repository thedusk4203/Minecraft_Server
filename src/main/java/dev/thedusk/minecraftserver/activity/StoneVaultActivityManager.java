package dev.thedusk.minecraftserver.activity;

import dev.thedusk.minecraftserver.boss.StoneWardenBossManager;
import dev.thedusk.minecraftserver.encounter.EncounterDefinition;
import dev.thedusk.minecraftserver.encounter.EncounterDefinitionRegistry;
import dev.thedusk.minecraftserver.encounter.EncounterParticipantReservationRegistry;
import dev.thedusk.minecraftserver.encounter.EncounterRun;
import dev.thedusk.minecraftserver.encounter.EncounterRunRepository;
import dev.thedusk.minecraftserver.encounter.EncounterRunResult;
import dev.thedusk.minecraftserver.identity.PlayerIdentityRepository;
import dev.thedusk.minecraftserver.persistence.PersistenceExecutor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class StoneVaultActivityManager implements Listener, AutoCloseable {
    public static final String ACTIVITY_ID = "stone_vault";

    private static final int NO_PARTICIPANT_GRACE_TICKS = 200;
    private static final double ENTRY_RADIUS = 12.0;
    private static final double VERTICAL_TOLERANCE = 16.0;

    private final JavaPlugin plugin;
    private final PlayerIdentityRepository identities;
    private final StructuredActivityDefinitionRegistry activityDefinitions;
    private final StructuredActivityRunRepository activityRuns;
    private final EncounterDefinitionRegistry encounterDefinitions;
    private final EncounterRunRepository encounterRuns;
    private final EncounterParticipantReservationRegistry reservations;
    private final StoneWardenBossManager bossManager;
    private final PersistenceExecutor persistenceExecutor;
    private final Logger logger;
    private final NamespacedKey runKey;
    private final NamespacedKey beatKey;
    private final NamespacedKey roleKey;

    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, UUID> mobToRun = new HashMap<>();
    private final Map<UUID, UUID> playerToRun = new HashMap<>();

    public StoneVaultActivityManager(
            JavaPlugin plugin,
            PlayerIdentityRepository identities,
            StructuredActivityDefinitionRegistry activityDefinitions,
            StructuredActivityRunRepository activityRuns,
            EncounterDefinitionRegistry encounterDefinitions,
            EncounterRunRepository encounterRuns,
            EncounterParticipantReservationRegistry reservations,
            StoneWardenBossManager bossManager,
            PersistenceExecutor persistenceExecutor,
            Logger logger
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.activityDefinitions = Objects.requireNonNull(activityDefinitions, "activityDefinitions");
        this.activityRuns = Objects.requireNonNull(activityRuns, "activityRuns");
        this.encounterDefinitions = Objects.requireNonNull(encounterDefinitions, "encounterDefinitions");
        this.encounterRuns = Objects.requireNonNull(encounterRuns, "encounterRuns");
        this.reservations = Objects.requireNonNull(reservations, "reservations");
        this.bossManager = Objects.requireNonNull(bossManager, "bossManager");
        this.persistenceExecutor = Objects.requireNonNull(persistenceExecutor, "persistenceExecutor");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.runKey = new NamespacedKey(plugin, "stone_vault_run");
        this.beatKey = new NamespacedKey(plugin, "stone_vault_beat");
        this.roleKey = new NamespacedKey(plugin, "stone_vault_role");
    }

    public void start(Player leader, List<Player> requestedParticipants) {
        requirePrimaryThread();
        Objects.requireNonNull(leader, "leader");
        Objects.requireNonNull(requestedParticipants, "requestedParticipants");

        StructuredActivityDefinition activityDefinition = activityDefinitions.require(ACTIVITY_ID);
        EncounterDefinition encounterDefinition = encounterDefinitions.require(
                activityDefinition.encounterId()
        );
        if (encounterDefinition.version() != activityDefinition.encounterVersion()) {
            throw new IllegalStateException("Stone Vault references a different encounter version");
        }

        List<Player> participants = normalizeParticipants(
                leader,
                requestedParticipants,
                activityDefinition
        );
        List<UUID> participantUuids = participants.stream()
                .map(Player::getUniqueId)
                .toList();
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
        UUID attachCorrelationId = UUID.randomUUID();

        leader.sendMessage("§7Đang tạo Stone Vault...");
        persistenceExecutor.submit(() -> persistStart(
                        activityDefinition,
                        encounterDefinition,
                        seeds,
                        startCorrelationId,
                        attachCorrelationId
                ))
                .whenComplete((result, failure) -> {
                    if (!plugin.isEnabled()) {
                        return;
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> finishStart(
                            leader.getUniqueId(),
                            seeds,
                            activityDefinition,
                            result,
                            failure
                    ));
                });
    }

    public String status() {
        requirePrimaryThread();
        if (sessions.isEmpty()) {
            return "No active Stone Vault activity.";
        }
        Session first = sessions.values().stream()
                .min(Comparator.comparing(session -> session.run().runId().toString()))
                .orElseThrow();
        return "Active Stone Vault activities: " + sessions.size()
                + " | run=" + first.run().runId()
                + " beat=" + first.currentBeat
                + " participants=" + first.participants().size()
                + " bossActive=" + first.bossActive;
    }

    public boolean isParticipant(UUID minecraftUuid) {
        requirePrimaryThread();
        return playerToRun.containsKey(Objects.requireNonNull(minecraftUuid, "minecraftUuid"));
    }

    private StartBundle persistStart(
            StructuredActivityDefinition activityDefinition,
            EncounterDefinition encounterDefinition,
            List<ParticipantSeed> seeds,
            UUID startCorrelationId,
            UUID attachCorrelationId
    ) {
        List<UUID> internalPlayerIds = new ArrayList<>(seeds.size());
        for (ParticipantSeed seed : seeds) {
            internalPlayerIds.add(
                    identities.recordSeen(seed.minecraftUuid(), seed.playerName()).playerId()
            );
        }

        EncounterRunResult encounter = encounterRuns.start(
                encounterDefinition,
                internalPlayerIds,
                startCorrelationId
        );
        try {
            StructuredActivityRunResult activity = activityRuns.attach(
                    activityDefinition,
                    encounterDefinition,
                    encounter.run().runId(),
                    attachCorrelationId
            );
            return new StartBundle(encounter.run(), activity.run());
        } catch (RuntimeException exception) {
            try {
                encounterRuns.fail(
                        encounter.run().runId(),
                        UUID.randomUUID(),
                        "ACTIVITY_ATTACH_FAILED"
                );
            } catch (RuntimeException terminalFailure) {
                exception.addSuppressed(terminalFailure);
            }
            throw exception;
        }
    }

    private void finishStart(
            UUID leaderUuid,
            List<ParticipantSeed> seeds,
            StructuredActivityDefinition definition,
            StartBundle result,
            Throwable failure
    ) {
        requirePrimaryThread();
        List<UUID> participantUuids = seeds.stream().map(ParticipantSeed::minecraftUuid).toList();
        Player leader = Bukkit.getPlayer(leaderUuid);

        if (failure != null) {
            reservations.release(participantUuids);
            logger.log(Level.SEVERE, "Failed to persist Stone Vault start", failure);
            if (leader != null) {
                leader.sendMessage("§cKhông thể tạo Stone Vault. Không có activity mob nào được spawn.");
            }
            return;
        }
        if (result == null) {
            reservations.release(participantUuids);
            logger.severe("Stone Vault start completed without a result");
            return;
        }

        List<Player> onlineParticipants = new ArrayList<>(seeds.size());
        for (ParticipantSeed seed : seeds) {
            Player player = Bukkit.getPlayer(seed.minecraftUuid());
            if (player == null || !player.isOnline() || player.isDead()) {
                failPersistedStart(
                        result.run().runId(),
                        "START_PARTICIPANT_UNAVAILABLE",
                        participantUuids
                );
                if (leader != null) {
                    leader.sendMessage("§cStone Vault bị hủy vì một thành viên không còn sẵn sàng.");
                }
                return;
            }
            if (playerToRun.containsKey(player.getUniqueId())) {
                failPersistedStart(
                        result.run().runId(),
                        "START_PARTICIPANT_CONFLICT",
                        participantUuids
                );
                return;
            }
            onlineParticipants.add(player);
        }

        Player activeLeader = Bukkit.getPlayer(leaderUuid);
        if (activeLeader == null || !activeLeader.isOnline() || activeLeader.isDead()) {
            failPersistedStart(result.run().runId(), "START_LEADER_UNAVAILABLE", participantUuids);
            return;
        }
        World world = activeLeader.getWorld();
        Location arenaCenter = activeLeader.getLocation().clone();
        for (Player player : onlineParticipants) {
            if (player.getWorld() != world
                    || player.getLocation().distanceSquared(arenaCenter) > ENTRY_RADIUS * ENTRY_RADIUS) {
                failPersistedStart(
                        result.run().runId(),
                        "START_PARTICIPANT_WORLD_OR_DISTANCE_MISMATCH",
                        participantUuids
                );
                activeLeader.sendMessage("§cStone Vault bị hủy vì party không còn ở cùng khu vực.");
                return;
            }
        }

        Session session = new Session(
                result.run(),
                definition,
                participantUuids,
                arenaCenter,
                result.activity().currentBeat()
        );
        sessions.put(result.run().runId(), session);
        participantUuids.forEach(uuid -> playerToRun.put(uuid, result.run().runId()));

        try {
            spawnWave(session);
        } catch (RuntimeException exception) {
            logger.log(Level.SEVERE, "Failed to spawn Stone Vault opening wave", exception);
            fail(session, "OUTER_SEAL_SPAWN_FAILED");
            return;
        }

        session.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(session), 1L, 1L);
        broadcast(session, "§6Stone Vault bắt đầu. §7Beat 1: phá Outer Seal.");
        logger.info("ACTIVITY_TELEMETRY event=started run=" + session.run().runId()
                + " activity=stone_vault participants=" + participantUuids.size());
        logger.info("ACTIVITY_TELEMETRY event=beat_started run=" + session.run().runId()
                + " beat=outer_seal mobs=" + session.activeMobIds.size());
    }

    private void tick(Session session) {
        requirePrimaryThread();
        if (!isActive(session) || session.terminalRequested || session.bossActive) {
            return;
        }

        List<Player> eligible = eligibleParticipants(session);
        if (eligible.isEmpty()) {
            session.noParticipantTicks++;
            if (session.noParticipantTicks >= NO_PARTICIPANT_GRACE_TICKS) {
                fail(session, "NO_ACTIVE_PARTICIPANTS");
            }
            return;
        }
        session.noParticipantTicks = 0;

        for (UUID entityId : List.copyOf(session.activeMobIds)) {
            Entity entity = Bukkit.getEntity(entityId);
            if (entity == null) {
                fail(session, "WAVE_ENTITY_MISSING");
                return;
            }
            if (!(entity instanceof Mob mob) || mob.isDead()) {
                continue;
            }
            enforceMobLeash(session, mob);
            Player target = nearestPlayer(mob.getLocation(), eligible);
            if (mob.getTarget() == null
                    || !(mob.getTarget() instanceof Player current)
                    || !session.participants().contains(current.getUniqueId())
                    || !insideArena(session, current.getLocation())) {
                mob.setTarget(target);
            }
        }
    }

    private void spawnWave(Session session) {
        requirePrimaryThread();
        if (session.currentBeat == StructuredActivityBeat.BOSS) {
            throw new IllegalStateException("Boss beat does not spawn Stone Vault wave mobs");
        }
        if (!session.activeMobIds.isEmpty()) {
            throw new IllegalStateException("Cannot spawn a new wave while the previous wave is active");
        }

        int total = session.currentBeat == StructuredActivityBeat.OUTER_SEAL
                ? session.definition().outerSealCount()
                : session.definition().innerGuardCount();
        for (int index = 0; index < total; index++) {
            boolean ranged = session.currentBeat == StructuredActivityBeat.INNER_GUARD
                    && index >= session.definition().innerGuardMeleeCount();
            Location spawn = waveSpawnLocation(session, index, total);
            Mob mob = ranged
                    ? spawn.getWorld().spawn(spawn, Skeleton.class)
                    : spawn.getWorld().spawn(spawn, Zombie.class);
            configureWaveMob(session, mob, ranged);
            session.activeMobIds.add(mob.getUniqueId());
            mobToRun.put(mob.getUniqueId(), session.run().runId());
        }
    }

    private void configureWaveMob(Session session, Mob mob, boolean ranged) {
        mob.setRemoveWhenFarAway(false);
        mob.setPersistent(false);
        mob.setCanPickupItems(false);
        mob.customName(Component.text(switch (session.currentBeat) {
            case OUTER_SEAL -> "Stone Sentinel";
            case INNER_GUARD -> ranged ? "Stone Archer" : "Stone Guard";
            case BOSS -> throw new IllegalStateException("Boss is owned by StoneWardenBossManager");
        }));
        mob.setCustomNameVisible(true);
        mob.getPersistentDataContainer().set(
                runKey,
                PersistentDataType.STRING,
                session.run().runId().toString()
        );
        mob.getPersistentDataContainer().set(
                beatKey,
                PersistentDataType.INTEGER,
                session.currentBeat.ordinalValue()
        );
        mob.getPersistentDataContainer().set(
                roleKey,
                PersistentDataType.STRING,
                ranged ? "ranged" : "melee"
        );
    }

    private Location waveSpawnLocation(Session session, int index, int total) {
        double radius = Math.min(
                Math.max(4.0, session.definition().arenaRadius() * 0.45),
                session.definition().arenaRadius() - 2.0
        );
        double angle = (Math.PI * 2.0 * index) / Math.max(1, total);
        Location candidate = session.arenaCenter().clone().add(
                Math.cos(angle) * radius,
                0.0,
                Math.sin(angle) * radius
        );
        if (!candidate.getBlock().isPassable()) {
            candidate.add(0.0, 1.0, 0.0);
        }
        if (!candidate.getBlock().isPassable()) {
            candidate = session.arenaCenter().clone().add(0.0, 1.0, 0.0);
        }
        return candidate;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onActivityMobDamage(EntityDamageEvent event) {
        UUID runId = mobToRun.get(event.getEntity().getUniqueId());
        if (runId == null) {
            return;
        }
        Session session = sessions.get(runId);
        if (session == null || session.terminalRequested || session.bossActive) {
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
    public void onActivityMobTarget(EntityTargetLivingEntityEvent event) {
        UUID runId = mobToRun.get(event.getEntity().getUniqueId());
        if (runId == null) {
            return;
        }
        Session session = sessions.get(runId);
        if (session == null || !(event.getTarget() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }
        if (!session.participants().contains(player.getUniqueId())
                || !insideArena(session, player.getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onActivityMobDeath(EntityDeathEvent event) {
        UUID runId = mobToRun.remove(event.getEntity().getUniqueId());
        if (runId == null) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);

        Session session = sessions.get(runId);
        if (session == null || session.terminalRequested || session.bossActive) {
            return;
        }
        session.activeMobIds.remove(event.getEntity().getUniqueId());
        if (session.activeMobIds.isEmpty() && !session.transitionInFlight) {
            advanceBeat(session);
        }
    }

    private void advanceBeat(Session session) {
        StructuredActivityBeat completedBeat = session.currentBeat;
        StructuredActivityBeat targetBeat = completedBeat.next();
        if (targetBeat == null) {
            return;
        }
        session.transitionInFlight = true;
        UUID correlationId = UUID.randomUUID();
        persistenceExecutor.submit(() -> activityRuns.advance(
                        session.run().runId(),
                        completedBeat,
                        targetBeat,
                        correlationId
                ))
                .whenComplete((result, failure) -> {
                    if (!plugin.isEnabled()) {
                        return;
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> finishAdvance(
                            session,
                            completedBeat,
                            targetBeat,
                            result,
                            failure
                    ));
                });
    }

    private void finishAdvance(
            Session session,
            StructuredActivityBeat completedBeat,
            StructuredActivityBeat targetBeat,
            StructuredActivityRunResult result,
            Throwable failure
    ) {
        requirePrimaryThread();
        if (!isActive(session) || session.terminalRequested || session.bossActive) {
            return;
        }
        session.transitionInFlight = false;
        if (failure != null) {
            logger.log(
                    Level.SEVERE,
                    "Failed to advance Stone Vault beat for run " + session.run().runId(),
                    failure
            );
            fail(session, "BEAT_TRANSITION_FAILED");
            return;
        }
        if (result == null || result.run().currentBeat() != targetBeat) {
            fail(session, "BEAT_TRANSITION_RESULT_INVALID");
            return;
        }

        session.currentBeat = targetBeat;
        logger.info("ACTIVITY_TELEMETRY event=beat_completed run=" + session.run().runId()
                + " beat=" + telemetryBeat(completedBeat));
        logger.info("ACTIVITY_TELEMETRY event=beat_started run=" + session.run().runId()
                + " beat=" + telemetryBeat(targetBeat));

        if (targetBeat == StructuredActivityBeat.BOSS) {
            activateBoss(session);
            return;
        }

        try {
            spawnWave(session);
        } catch (RuntimeException exception) {
            logger.log(Level.SEVERE, "Failed to spawn Stone Vault wave", exception);
            fail(session, "INNER_GUARD_SPAWN_FAILED");
            return;
        }
        broadcast(session, "§6Outer Seal đã mở. §7Beat 2: đánh bại Inner Guard.");
    }

    private void activateBoss(Session session) {
        boolean activated;
        try {
            activated = bossManager.activateExistingRun(
                    session.run(),
                    session.participants(),
                    session.arenaCenter(),
                    this::onBossTerminal
            );
        } catch (RuntimeException exception) {
            logger.log(
                    Level.SEVERE,
                    "Failed to activate Stone Warden on Stone Vault run " + session.run().runId(),
                    exception
            );
            activated = false;
        }
        if (!activated) {
            fail(session, "BOSS_ACTIVATION_FAILED");
            return;
        }

        session.bossActive = true;
        if (session.task != null) {
            session.task.cancel();
            session.task = null;
        }
        broadcast(session, "§6Inner Guard đã bị đánh bại. §7Stone Warden thức tỉnh.");
        logger.info("ACTIVITY_TELEMETRY event=boss_activated run=" + session.run().runId()
                + " boss=stone_warden participants=" + session.participants().size());
    }

    private void onBossTerminal(UUID runId, boolean completed, String reason) {
        requirePrimaryThread();
        Session session = sessions.get(runId);
        if (session == null) {
            return;
        }
        session.terminalRequested = true;
        broadcast(
                session,
                completed
                        ? "§aStone Vault hoàn thành. §7Reward Stone Warden đang được xử lý."
                        : "§cStone Vault thất bại và đã reset."
        );
        cleanupActivityState(session, false, false);
        logger.info("ACTIVITY_TELEMETRY event=" + (completed ? "completed" : "failed")
                + " run=" + runId
                + (reason == null ? "" : " reason=" + reason));
    }

    private void fail(Session session, String reason) {
        requirePrimaryThread();
        if (!isActive(session) || session.terminalRequested || session.bossActive) {
            return;
        }
        session.terminalRequested = true;
        broadcast(session, "§cStone Vault thất bại và đã reset.");
        cleanupActivityState(session, true, false);

        UUID correlationId = UUID.randomUUID();
        persistenceExecutor.submit(() -> encounterRuns.fail(
                        session.run().runId(),
                        correlationId,
                        reason
                ))
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        logger.log(
                                Level.SEVERE,
                                "Failed to persist Stone Vault failure for run " + session.run().runId(),
                                failure
                        );
                        return;
                    }
                    reservations.release(session.participants());
                    logger.info("ACTIVITY_TELEMETRY event=failed run=" + session.run().runId()
                            + " reason=" + reason);
                });
    }

    private void failPersistedStart(UUID runId, String reason, List<UUID> participantUuids) {
        UUID correlationId = UUID.randomUUID();
        persistenceExecutor.submit(() -> encounterRuns.fail(runId, correlationId, reason))
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        logger.log(
                                Level.SEVERE,
                                "Failed to mark unspawned Stone Vault failed: " + runId,
                                failure
                        );
                        return;
                    }
                    reservations.release(participantUuids);
                });
    }

    private void cleanupActivityState(
            Session session,
            boolean removeMobs,
            boolean releaseReservations
    ) {
        sessions.remove(session.run().runId());
        session.participants().forEach(uuid -> playerToRun.remove(uuid, session.run().runId()));
        if (session.task != null) {
            session.task.cancel();
            session.task = null;
        }
        if (removeMobs) {
            for (UUID entityId : List.copyOf(session.activeMobIds)) {
                mobToRun.remove(entityId);
                Entity entity = Bukkit.getEntity(entityId);
                if (entity != null && !entity.isDead()) {
                    entity.remove();
                }
            }
        } else {
            session.activeMobIds.forEach(mobToRun::remove);
        }
        session.activeMobIds.clear();
        if (releaseReservations) {
            reservations.release(session.participants());
        }
    }

    private void enforceMobLeash(Session session, Mob mob) {
        if (!insideArena(session, mob.getLocation())) {
            mob.teleport(session.arenaCenter());
        }
    }

    private List<Player> eligibleParticipants(Session session) {
        return session.participants().stream()
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull)
                .filter(Player::isOnline)
                .filter(player -> !player.isDead())
                .filter(player -> insideArena(session, player.getLocation()))
                .toList();
    }

    private boolean insideArena(Session session, Location location) {
        if (location.getWorld() != session.arenaCenter().getWorld()) {
            return false;
        }
        double dx = location.getX() - session.arenaCenter().getX();
        double dz = location.getZ() - session.arenaCenter().getZ();
        double dy = Math.abs(location.getY() - session.arenaCenter().getY());
        return dx * dx + dz * dz
                <= session.definition().arenaRadius() * session.definition().arenaRadius()
                && dy <= VERTICAL_TOLERANCE;
    }

    private List<Player> normalizeParticipants(
            Player leader,
            List<Player> requestedParticipants,
            StructuredActivityDefinition definition
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
            if (player.getLocation().distanceSquared(leader.getLocation()) > ENTRY_RADIUS * ENTRY_RADIUS) {
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
                    "Stone Vault requires " + definition.minPlayers() + "-" + definition.maxPlayers() + " players"
            );
        }
        return List.copyOf(normalized);
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

    private void broadcast(Session session, String message) {
        for (UUID participant : session.participants()) {
            Player player = Bukkit.getPlayer(participant);
            if (player != null && player.isOnline()) {
                player.sendMessage(message);
            }
        }
    }

    private boolean isActive(Session session) {
        return sessions.get(session.run().runId()) == session;
    }

    private static Player nearestPlayer(Location origin, List<Player> players) {
        return players.stream()
                .min(Comparator.comparingDouble(player -> player.getLocation().distanceSquared(origin)))
                .orElseThrow();
    }

    private static String telemetryBeat(StructuredActivityBeat beat) {
        return switch (beat) {
            case OUTER_SEAL -> "outer_seal";
            case INNER_GUARD -> "inner_guard";
            case BOSS -> "boss";
        };
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Stone Vault runtime must be accessed from the Bukkit main thread");
        }
    }

    @Override
    public void close() {
        requirePrimaryThread();
        for (Session session : List.copyOf(sessions.values())) {
            session.terminalRequested = true;
            cleanupActivityState(session, !session.bossActive, !session.bossActive);
        }
    }

    private record ParticipantSeed(UUID minecraftUuid, String playerName) {
    }

    private record StartBundle(EncounterRun run, StructuredActivityRun activity) {
    }

    private static final class Session {
        private final EncounterRun run;
        private final StructuredActivityDefinition definition;
        private final List<UUID> participants;
        private final Location arenaCenter;
        private final Set<UUID> activeMobIds = new HashSet<>();

        private StructuredActivityBeat currentBeat;
        private BukkitTask task;
        private int noParticipantTicks;
        private boolean transitionInFlight;
        private boolean bossActive;
        private boolean terminalRequested;

        private Session(
                EncounterRun run,
                StructuredActivityDefinition definition,
                List<UUID> participants,
                Location arenaCenter,
                StructuredActivityBeat currentBeat
        ) {
            this.run = Objects.requireNonNull(run, "run");
            this.definition = Objects.requireNonNull(definition, "definition");
            this.participants = List.copyOf(participants);
            this.arenaCenter = arenaCenter.clone();
            this.currentBeat = Objects.requireNonNull(currentBeat, "currentBeat");
        }

        private EncounterRun run() {
            return run;
        }

        private StructuredActivityDefinition definition() {
            return definition;
        }

        private List<UUID> participants() {
            return participants;
        }

        private Location arenaCenter() {
            return arenaCenter;
        }
    }
}
