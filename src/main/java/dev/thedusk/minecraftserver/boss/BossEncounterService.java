package dev.thedusk.minecraftserver.boss;

import dev.thedusk.minecraftserver.encounter.EncounterDefinition;
import dev.thedusk.minecraftserver.encounter.EncounterDefinitionRegistry;
import dev.thedusk.minecraftserver.encounter.EncounterRun;
import dev.thedusk.minecraftserver.encounter.EncounterRunRepository;
import dev.thedusk.minecraftserver.identity.PlayerIdentityRepository;
import dev.thedusk.minecraftserver.persistence.PersistenceExecutor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

public final class BossEncounterService implements Listener, CommandExecutor, AutoCloseable {
    private static final int MAX_TERMINAL_PERSIST_RETRIES = 3;
    private static final long TERMINAL_RETRY_DELAY_TICKS = 20L;

    private final JavaPlugin plugin;
    private final BossDefinitionRegistry bossDefinitions;
    private final EncounterDefinitionRegistry encounterDefinitions;
    private final EncounterRunRepository encounterRuns;
    private final PlayerIdentityRepository identities;
    private final BossTelemetryRepository telemetry;
    private final PersistenceExecutor executor;
    private final Logger logger;
    private final AtomicBoolean startPending = new AtomicBoolean();
    private final BukkitTask ticker;

    private ActiveBoss active;

    public BossEncounterService(
            JavaPlugin plugin,
            BossDefinitionRegistry bossDefinitions,
            EncounterDefinitionRegistry encounterDefinitions,
            EncounterRunRepository encounterRuns,
            PlayerIdentityRepository identities,
            BossTelemetryRepository telemetry,
            PersistenceExecutor executor,
            Logger logger
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.bossDefinitions = Objects.requireNonNull(bossDefinitions, "bossDefinitions");
        this.encounterDefinitions = Objects.requireNonNull(encounterDefinitions, "encounterDefinitions");
        this.encounterRuns = Objects.requireNonNull(encounterRuns, "encounterRuns");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.logger = Objects.requireNonNull(logger, "logger");
        validateEntityTypes();
        this.ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            showStatus(sender);
            return true;
        }
        if (args[0].equalsIgnoreCase("start")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("This boss command requires an online player.");
                return true;
            }
            String bossId = args.length >= 2 ? args[1] : "stone_warden";
            start(player, bossId);
            return true;
        }
        sender.sendMessage("Usage: /boss <start [boss-id]|status>");
        return true;
    }

    private void start(Player starter, String bossId) {
        if (active != null) {
            starter.sendMessage("§cĐang có một boss encounter hoạt động. Dùng §f/boss status§c để xem.");
            return;
        }
        if (!startPending.compareAndSet(false, true)) {
            starter.sendMessage("§eMột boss encounter đang được chuẩn bị.");
            return;
        }

        final BossDefinition bossDefinition;
        final EncounterDefinition encounterDefinition;
        try {
            bossDefinition = bossDefinitions.require(bossId);
            encounterDefinition = encounterDefinitions.require(bossDefinition.encounterId());
        } catch (RuntimeException exception) {
            startPending.set(false);
            starter.sendMessage("§cBoss definition không hợp lệ: " + exception.getMessage());
            return;
        }

        List<ParticipantSnapshot> participants = selectParticipants(starter, bossDefinition, encounterDefinition);
        if (participants.size() < encounterDefinition.minPlayers()) {
            startPending.set(false);
            starter.sendMessage("§cKhông đủ người chơi hợp lệ để bắt đầu encounter.");
            return;
        }

        Location origin = chooseOrigin(starter);
        UUID startCorrelationId = UUID.randomUUID();
        starter.sendMessage("§7Đang tạo durable encounter run cho §f" + bossDefinition.displayName() + "§7...");

        executor.submit(() -> persistStart(encounterDefinition, participants, startCorrelationId))
                .whenComplete((result, failure) -> runOnMain(() -> {
                    startPending.set(false);
                    if (failure != null) {
                        logger.log(Level.SEVERE, "Failed to persist boss encounter start", unwrap(failure));
                        Player onlineStarter = plugin.getServer().getPlayer(starter.getUniqueId());
                        if (onlineStarter != null) {
                            onlineStarter.sendMessage("§cKhông thể bắt đầu boss vì durable run không tạo được.");
                        }
                        return;
                    }
                    activatePersistedRun(result, bossDefinition, origin, starter.getUniqueId());
                }));
    }

    private StartResult persistStart(
            EncounterDefinition encounterDefinition,
            List<ParticipantSnapshot> participants,
            UUID correlationId
    ) {
        LinkedHashMap<UUID, UUID> internalByMinecraft = new LinkedHashMap<>();
        for (ParticipantSnapshot participant : participants) {
            var identity = identities.recordSeen(participant.minecraftUuid(), participant.name());
            internalByMinecraft.put(participant.minecraftUuid(), identity.playerId());
        }
        EncounterRun run = encounterRuns.start(
                encounterDefinition,
                List.copyOf(internalByMinecraft.values()),
                correlationId
        ).run();
        return new StartResult(run, Map.copyOf(internalByMinecraft));
    }

    private void activatePersistedRun(
            StartResult result,
            BossDefinition definition,
            Location origin,
            UUID starterMinecraftUuid
    ) {
        if (!plugin.isEnabled()) {
            failPersistedRun(result.run().runId(), "PLUGIN_DISABLED_BEFORE_SPAWN");
            return;
        }
        if (active != null) {
            failPersistedRun(result.run().runId(), "CONCURRENT_ACTIVE_BOSS");
            return;
        }
        World world = origin.getWorld();
        if (world == null) {
            failPersistedRun(result.run().runId(), "SPAWN_WORLD_MISSING");
            return;
        }

        Entity spawned = null;
        try {
            EntityType type = EntityType.valueOf(definition.entityType());
            spawned = world.spawnEntity(origin, type);
            if (!(spawned instanceof LivingEntity living)) {
                spawned.remove();
                failPersistedRun(result.run().runId(), "SPAWNED_ENTITY_NOT_LIVING");
                return;
            }
            living.setCustomName(definition.displayName());
            living.setCustomNameVisible(true);
            living.setPersistent(false);

            BossSessionState state = new BossSessionState(
                    result.run().runId(),
                    living.getUniqueId(),
                    result.run().startedAt(),
                    result.internalPlayerIdByMinecraftUuid()
            );
            active = new ActiveBoss(definition, state, living, origin.clone());
            broadcast(active, "§6" + definition.displayName() + " §fđã xuất hiện. Không có reward ở Phase 4B; đây là combat validation.");
            broadcast(active, "§7Rời server hoặc chết sẽ loại bạn khỏi attempt hiện tại. Rời arena sẽ bị kéo trở lại.");
            logger.info("Boss encounter started run=" + state.runId()
                    + " boss=" + definition.id()
                    + " participants=" + state.participantMinecraftUuids().size()
                    + " starter=" + starterMinecraftUuid);
        } catch (RuntimeException exception) {
            if (spawned != null && spawned.isValid()) {
                spawned.remove();
            }
            logger.log(Level.SEVERE, "Failed to spawn boss for run " + result.run().runId(), exception);
            failPersistedRun(result.run().runId(), "SPAWN_FAILED");
        }
    }

    private void tick() {
        ActiveBoss current = active;
        if (current == null || current.state.terminalRequested()) {
            return;
        }

        current.elapsedTicks++;
        if (current.elapsedTicks >= encounterDefinitions.require(current.definition.encounterId()).timeoutSeconds() * 20L) {
            requestTerminal(current, TerminalKind.FAILED, "TIMEOUT");
            return;
        }
        if (current.entity.isDead()) {
            return;
        }
        if (!current.entity.isValid()) {
            requestTerminal(current, TerminalKind.FAILED, "BOSS_REMOVED");
            return;
        }

        enforceBossBoundary(current);
        enforceParticipantBoundaries(current);
        if (current.state.remainingParticipants() == 0) {
            requestTerminal(current, TerminalKind.FAILED, "PARTY_WIPE");
            return;
        }

        if (current.elapsedTicks % 20L == 0L) {
            retargetBoss(current);
        }
        tickGroundSlam(current);
        tickPressureRing(current);
    }

    private void tickGroundSlam(ActiveBoss current) {
        BossDefinition definition = current.definition;
        long phase = current.elapsedTicks % definition.slamIntervalTicks();
        long telegraphAt = definition.slamIntervalTicks() - definition.slamWindupTicks();
        if (phase == telegraphAt) {
            current.slamResolveTick = current.elapsedTicks + definition.slamWindupTicks();
            broadcast(current, "§e[Telegraph] §6Ground Slam §7sắp nổ — rời khỏi boss ít nhất "
                    + format(definition.slamRadius()) + " block.");
        }
        if (current.slamResolveTick == current.elapsedTicks) {
            current.slamResolveTick = -1L;
            Location bossLocation = current.entity.getLocation();
            forEachCombatant(current, player -> {
                if (sameWorld(player.getLocation(), bossLocation)
                        && player.getLocation().distanceSquared(bossLocation)
                        <= square(definition.slamRadius())) {
                    player.damage(definition.slamDamage(), current.entity);
                }
            });
        }
    }

    private void tickPressureRing(ActiveBoss current) {
        BossDefinition definition = current.definition;
        long phase = current.elapsedTicks % definition.ringIntervalTicks();
        long telegraphAt = definition.ringIntervalTicks() - definition.ringWindupTicks();
        if (phase == telegraphAt) {
            current.ringResolveTick = current.elapsedTicks + definition.ringWindupTicks();
            broadcast(current, "§e[Telegraph] §dPressure Ring §7sắp khép — đứng gần boss trong "
                    + format(definition.ringSafeRadius()) + " block hoặc thoát hẳn vòng "
                    + format(definition.ringOuterRadius()) + " block.");
        }
        if (current.ringResolveTick == current.elapsedTicks) {
            current.ringResolveTick = -1L;
            Location bossLocation = current.entity.getLocation();
            double safeSquared = square(definition.ringSafeRadius());
            double outerSquared = square(definition.ringOuterRadius());
            forEachCombatant(current, player -> {
                if (!sameWorld(player.getLocation(), bossLocation)) {
                    return;
                }
                double distanceSquared = player.getLocation().distanceSquared(bossLocation);
                if (distanceSquared > safeSquared && distanceSquared <= outerSquared) {
                    player.damage(definition.ringDamage(), current.entity);
                }
            });
        }
    }

    private void enforceBossBoundary(ActiveBoss current) {
        Location location = current.entity.getLocation();
        if (!sameWorld(location, current.origin)
                || location.distanceSquared(current.origin) > square(current.definition.arenaRadius())) {
            current.entity.teleport(current.origin);
            broadcast(current, "§7Stone Warden bị kéo lại tâm arena (anti-cheese boundary).");
        }
    }

    private void enforceParticipantBoundaries(ActiveBoss current) {
        for (UUID minecraftUuid : current.state.participantMinecraftUuids()) {
            if (current.state.isEliminated(minecraftUuid)) {
                continue;
            }
            Player player = plugin.getServer().getPlayer(minecraftUuid);
            if (player == null || !player.isOnline()) {
                eliminateParticipant(current, minecraftUuid, "DISCONNECT");
                continue;
            }
            if (player.isDead()) {
                eliminateParticipant(current, minecraftUuid, "DEATH");
                continue;
            }
            Location playerLocation = player.getLocation();
            if (!sameWorld(playerLocation, current.origin)) {
                eliminateParticipant(current, minecraftUuid, "WORLD_EXIT");
                continue;
            }
            if (playerLocation.distanceSquared(current.origin) > square(current.definition.arenaRadius())) {
                player.teleport(current.origin);
                player.sendMessage("§cArena boundary: bạn đã được kéo trở lại encounter.");
            }
        }
    }

    private void retargetBoss(ActiveBoss current) {
        if (!(current.entity instanceof Mob mob)) {
            return;
        }
        Player nearest = null;
        double bestDistance = Double.MAX_VALUE;
        for (UUID minecraftUuid : current.state.participantMinecraftUuids()) {
            if (current.state.isEliminated(minecraftUuid)) {
                continue;
            }
            Player player = plugin.getServer().getPlayer(minecraftUuid);
            if (player == null || !player.isOnline() || player.isDead()) {
                continue;
            }
            if (!sameWorld(player.getLocation(), current.entity.getLocation())) {
                continue;
            }
            double distance = player.getLocation().distanceSquared(current.entity.getLocation());
            if (distance < bestDistance) {
                bestDistance = distance;
                nearest = player;
            }
        }
        mob.setTarget(nearest);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBossDamage(EntityDamageEvent event) {
        ActiveBoss current = active;
        if (current == null || !event.getEntity().getUniqueId().equals(current.state.bossEntityId())) {
            return;
        }
        if (event instanceof EntityDamageByEntityEvent byEntity && isEligibleAttacker(current, byEntity.getDamager())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBossAttacksOutsider(EntityDamageByEntityEvent event) {
        ActiveBoss current = active;
        if (current == null || !event.getDamager().getUniqueId().equals(current.state.bossEntityId())) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)
                || !current.state.isParticipant(player.getUniqueId())
                || current.state.isEliminated(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBossDeath(EntityDeathEvent event) {
        ActiveBoss current = active;
        if (current == null || !event.getEntity().getUniqueId().equals(current.state.bossEntityId())) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        requestTerminal(current, TerminalKind.COMPLETED, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onParticipantDeath(PlayerDeathEvent event) {
        ActiveBoss current = active;
        if (current == null) {
            return;
        }
        eliminateParticipant(current, event.getEntity().getUniqueId(), "DEATH");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onParticipantQuit(PlayerQuitEvent event) {
        ActiveBoss current = active;
        if (current == null) {
            return;
        }
        eliminateParticipant(current, event.getPlayer().getUniqueId(), "DISCONNECT");
    }

    private boolean isEligibleAttacker(ActiveBoss current, Entity damager) {
        Player attacker = null;
        if (damager instanceof Player player) {
            attacker = player;
        } else if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            attacker = player;
        }
        if (attacker == null
                || !current.state.isParticipant(attacker.getUniqueId())
                || current.state.isEliminated(attacker.getUniqueId())) {
            return false;
        }
        Location location = attacker.getLocation();
        return sameWorld(location, current.origin)
                && location.distanceSquared(current.origin) <= square(current.definition.arenaRadius());
    }

    private void eliminateParticipant(ActiveBoss current, UUID minecraftUuid, String cause) {
        if (active != current || current.state.terminalRequested() || !current.state.eliminate(minecraftUuid)) {
            return;
        }
        UUID internalPlayerId = current.state.internalPlayerId(minecraftUuid);
        UUID correlationId = UUID.randomUUID();
        executor.run(() -> telemetry.participantEliminated(
                        current.state.runId(),
                        internalPlayerId,
                        cause,
                        correlationId
                ))
                .exceptionally(failure -> {
                    logger.log(Level.WARNING, "Failed to persist boss participant telemetry", unwrap(failure));
                    return null;
                });
        broadcast(current, "§cMột participant đã bị loại: §f" + cause
                + " §7(remaining=" + current.state.remainingParticipants() + ")");
        if (current.state.remainingParticipants() == 0) {
            requestTerminal(current, TerminalKind.FAILED, "PARTY_WIPE");
        }
    }

    private void requestTerminal(ActiveBoss current, TerminalKind kind, String reason) {
        if (active != current || !current.state.beginTerminal()) {
            return;
        }
        PendingTerminal pending = new PendingTerminal(kind, reason, UUID.randomUUID());
        current.pendingTerminal = pending;
        if (kind != TerminalKind.COMPLETED && current.entity.isValid()) {
            current.entity.remove();
        }
        persistTerminal(current, pending, 0);
    }

    private void persistTerminal(ActiveBoss current, PendingTerminal pending, int retry) {
        executor.submit(() -> {
                    if (pending.kind() == TerminalKind.COMPLETED) {
                        return encounterRuns.complete(current.state.runId(), pending.correlationId());
                    }
                    return encounterRuns.fail(
                            current.state.runId(),
                            pending.correlationId(),
                            Objects.requireNonNull(pending.reason(), "terminal reason")
                    );
                })
                .whenComplete((result, failure) -> runOnMain(() -> {
                    if (active != current) {
                        return;
                    }
                    if (failure != null) {
                        if (retry < MAX_TERMINAL_PERSIST_RETRIES && plugin.isEnabled()) {
                            logger.log(
                                    Level.WARNING,
                                    "Boss terminal persistence failed; retrying run=" + current.state.runId()
                                            + " attempt=" + (retry + 1),
                                    unwrap(failure)
                            );
                            plugin.getServer().getScheduler().runTaskLater(
                                    plugin,
                                    () -> persistTerminal(current, pending, retry + 1),
                                    TERMINAL_RETRY_DELAY_TICKS
                            );
                            return;
                        }
                        logger.log(
                                Level.SEVERE,
                                "Boss terminal persistence exhausted retries; startup recovery will reconcile run="
                                        + current.state.runId(),
                                unwrap(failure)
                        );
                        cleanupActive(current, "terminal persistence exhausted");
                        return;
                    }

                    long durationMillis = Math.max(
                            0L,
                            Duration.between(current.state.startedAt(), Instant.now()).toMillis()
                    );
                    executor.run(() -> telemetry.attemptFinished(
                                    current.state.runId(),
                                    pending.kind().name(),
                                    pending.reason(),
                                    durationMillis,
                                    pending.correlationId()
                            ))
                            .exceptionally(telemetryFailure -> {
                                logger.log(Level.WARNING, "Failed to persist boss completion telemetry", unwrap(telemetryFailure));
                                return null;
                            });
                    broadcast(current, pending.kind() == TerminalKind.COMPLETED
                            ? "§aStone Warden đã bị hạ. §7Phase 4B không phát reward."
                            : "§cBoss encounter thất bại: §f" + pending.reason());
                    cleanupActive(current, "terminal " + pending.kind());
                }));
    }

    private void failPersistedRun(UUID runId, String reason) {
        executor.run(() -> encounterRuns.fail(runId, UUID.randomUUID(), reason))
                .exceptionally(failure -> {
                    logger.log(Level.SEVERE, "Failed to terminalize persisted boss start " + runId, unwrap(failure));
                    return null;
                });
    }

    private void cleanupActive(ActiveBoss current, String reason) {
        if (active != current) {
            return;
        }
        if (current.entity.isValid()) {
            current.entity.remove();
        }
        active = null;
        logger.info("Boss encounter cleaned run=" + current.state.runId() + " reason=" + reason);
    }

    private void showStatus(CommandSender sender) {
        ActiveBoss current = active;
        if (current == null) {
            sender.sendMessage(startPending.get()
                    ? "Boss encounter: PREPARING"
                    : "Boss encounter: IDLE");
            return;
        }
        sender.sendMessage("Boss encounter: ACTIVE"
                + " | boss=" + current.definition.id()
                + " | run=" + current.state.runId()
                + " | remaining=" + current.state.remainingParticipants()
                + "/" + current.state.participantMinecraftUuids().size()
                + " | health=" + format(current.entity.getHealth())
                + " | elapsed=" + (current.elapsedTicks / 20L) + "s");
    }

    private List<ParticipantSnapshot> selectParticipants(
            Player starter,
            BossDefinition bossDefinition,
            EncounterDefinition encounterDefinition
    ) {
        List<Player> nearby = new ArrayList<>();
        for (Player player : starter.getWorld().getPlayers()) {
            if (!player.isOnline() || player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            if (player.getLocation().distanceSquared(starter.getLocation()) <= square(bossDefinition.joinRadius())) {
                nearby.add(player);
            }
        }
        nearby.sort(Comparator.comparing(player -> player.getUniqueId().toString()));

        List<ParticipantSnapshot> selected = new ArrayList<>();
        selected.add(new ParticipantSnapshot(starter.getUniqueId(), starter.getName()));
        for (Player player : nearby) {
            if (player.getUniqueId().equals(starter.getUniqueId())) {
                continue;
            }
            if (selected.size() >= encounterDefinition.maxPlayers()) {
                break;
            }
            selected.add(new ParticipantSnapshot(player.getUniqueId(), player.getName()));
        }
        return List.copyOf(selected);
    }

    private static Location chooseOrigin(Player starter) {
        Location playerLocation = starter.getLocation();
        Vector facing = playerLocation.getDirection().setY(0.0);
        if (facing.lengthSquared() < 0.01) {
            facing = new Vector(1.0, 0.0, 0.0);
        }
        facing.normalize().multiply(6.0);
        return playerLocation.clone().add(facing);
    }

    private void forEachCombatant(ActiveBoss current, java.util.function.Consumer<Player> consumer) {
        for (UUID minecraftUuid : current.state.participantMinecraftUuids()) {
            if (current.state.isEliminated(minecraftUuid)) {
                continue;
            }
            Player player = plugin.getServer().getPlayer(minecraftUuid);
            if (player != null && player.isOnline() && !player.isDead()) {
                consumer.accept(player);
            }
        }
    }

    private void broadcast(ActiveBoss current, String message) {
        forEachCombatant(current, player -> player.sendMessage(message));
    }

    private void validateEntityTypes() {
        for (BossDefinition definition : bossDefinitions.definitions()) {
            EntityType type;
            try {
                type = EntityType.valueOf(definition.entityType());
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException(
                        "Boss '" + definition.id() + "' references unknown EntityType " + definition.entityType(),
                        exception
                );
            }
            Class<? extends Entity> entityClass = type.getEntityClass();
            if (entityClass == null || !LivingEntity.class.isAssignableFrom(entityClass)) {
                throw new IllegalStateException(
                        "Boss '" + definition.id() + "' EntityType must be a LivingEntity: " + type
                );
            }
        }
    }

    private void runOnMain(Runnable runnable) {
        if (!plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, runnable);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean sameWorld(Location first, Location second) {
        return first.getWorld() != null && first.getWorld().equals(second.getWorld());
    }

    private static double square(double value) {
        return value * value;
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    @Override
    public void close() {
        ticker.cancel();
        ActiveBoss current = active;
        active = null;
        if (current != null && current.entity.isValid()) {
            current.entity.remove();
        }
        startPending.set(false);
    }

    private enum TerminalKind {
        COMPLETED,
        FAILED
    }

    private record ParticipantSnapshot(UUID minecraftUuid, String name) {
    }

    private record StartResult(EncounterRun run, Map<UUID, UUID> internalPlayerIdByMinecraftUuid) {
    }

    private record PendingTerminal(TerminalKind kind, String reason, UUID correlationId) {
    }

    private static final class ActiveBoss {
        private final BossDefinition definition;
        private final BossSessionState state;
        private final LivingEntity entity;
        private final Location origin;
        private long elapsedTicks;
        private long slamResolveTick = -1L;
        private long ringResolveTick = -1L;
        private PendingTerminal pendingTerminal;

        private ActiveBoss(
                BossDefinition definition,
                BossSessionState state,
                LivingEntity entity,
                Location origin
        ) {
            this.definition = definition;
            this.state = state;
            this.entity = entity;
            this.origin = origin;
        }
    }
}
