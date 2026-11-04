package dev.thedusk.minecraftserver.e2efault;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Built in CI after production smoke and loaded ONLY in compose.e2e.yaml.
 * This plugin deliberately injects impossible/rare runtime failures. It is
 * never compiled into the MinecraftServerCore production artifact.
 */
public final class StoneVaultFaultPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private Plugin core;
    private NamespacedKey runKey;
    private NamespacedKey beatKey;
    private final Set<UUID> replayOnDeath = new HashSet<>();
    private final Set<UUID> rejectBoss = new HashSet<>();
    private EntityDeathEvent capturedDeath;
    private UUID capturedRun;

    @Override
    public void onEnable() {
        if (!"true".equalsIgnoreCase(System.getenv("STONE_VAULT_E2E_FAULTS_ENABLED"))) {
            getLogger().severe("Refusing to load test-only fault plugin outside the E2E stack");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        core = getServer().getPluginManager().getPlugin("MinecraftServerCore");
        if (core == null || !core.isEnabled()) {
            throw new IllegalStateException("MinecraftServerCore must load before the E2E fault plugin");
        }
        runKey = new NamespacedKey(core, "stone_vault_run");
        beatKey = new NamespacedKey(core, "stone_vault_beat");
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("vaultfault").setExecutor(this);
        getLogger().warning("E2E-ONLY fault injection helper enabled; never deploy to production");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Only console/RCON may trigger fault injection, never an in-game player.
        if (sender instanceof Player) {
            sender.sendMessage("Test-only command is not available to players");
            return true;
        }
        if (args.length != 2) {
            sender.sendMessage("Usage: vaultfault <remove|killone|clearwave|rejectboss|armduplicate|replay> <run-uuid>");
            return true;
        }
        try {
            UUID runId = UUID.fromString(args[1]);
            switch (args[0]) {
                case "armduplicate" -> {
                    replayOnDeath.add(runId);
                    sender.sendMessage("E2E_FAULT_DUPLICATE_ARMED run=" + runId);
                }
                case "rejectboss" -> {
                    rejectBoss.add(runId);
                    sender.sendMessage("E2E_FAULT_BOSS_REJECTION_ARMED run=" + runId);
                }
                case "killone" -> {
                    List<LivingEntity> mobs = waveMobs(runId);
                    if (mobs.isEmpty()) throw new IllegalStateException("No live activity mob to kill");
                    mobs.getFirst().setHealth(0.0);
                    sender.sendMessage("E2E_FAULT_KILL_ONE run=" + runId);
                }
                case "remove" -> {
                    List<LivingEntity> mobs = waveMobs(runId);
                    if (mobs.isEmpty()) throw new IllegalStateException("No live activity mob to remove");
                    mobs.getFirst().remove(); // No EntityDeathEvent: tick must detect WAVE_ENTITY_MISSING.
                    sender.sendMessage("E2E_FAULT_REMOVE_WITHOUT_DEATH run=" + runId);
                }
                case "clearwave" -> {
                    List<LivingEntity> mobs = waveMobs(runId);
                    if (mobs.isEmpty()) throw new IllegalStateException("No live activity wave to fault-clear");
                    for (LivingEntity mob : mobs) {
                        mob.setHealth(0.0); // Simulated death event, not the positive E2E combat path.
                    }
                    sender.sendMessage("E2E_FAULT_CLEAR_WAVE run=" + runId + " count=" + mobs.size());
                }
                case "replay" -> {
                    if (!runId.equals(capturedRun) || capturedDeath == null) {
                        throw new IllegalStateException("No captured event for run " + runId);
                    }
                    replayCallback(capturedDeath);
                    sender.sendMessage("E2E_FAULT_STALE_DEATH_REPLAY run=" + runId);
                }
                default -> sender.sendMessage("Unknown E2E fault command");
            }
        } catch (RuntimeException exception) {
            getLogger().severe("Fault command failed: " + exception);
            sender.sendMessage("E2E_FAULT_ERROR " + exception.getMessage());
        }
        return true;
    }

    private List<LivingEntity> waveMobs(UUID runId) {
        String expected = runId.toString();
        List<LivingEntity> result = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof LivingEntity living && !living.isDead()
                        && expected.equals(entity.getPersistentDataContainer()
                                .get(runKey, PersistentDataType.STRING))) {
                    result.add(living);
                }
            }
        }
        return result;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWaveDeath(EntityDeathEvent event) {
        String id = event.getEntity().getPersistentDataContainer()
                .get(runKey, PersistentDataType.STRING);
        if (id == null) return;
        UUID runId = UUID.fromString(id);
        capturedDeath = event;
        capturedRun = runId;
        // Core's MONITOR event handler registered earlier; replaying the same event
        // cannot advance a second beat or spawn a second wave.
        if (replayOnDeath.contains(runId)) {
            replayCallback(event);
            getLogger().info("E2E_FAULT_DUPLICATE_DEATH_REPLAY run=" + runId);
        }
        Integer beat = event.getEntity().getPersistentDataContainer()
                .get(beatKey, PersistentDataType.INTEGER);
        if (Integer.valueOf(2).equals(beat) && rejectBoss.remove(runId)) {
            // Test-only fault: revoke the process-local reservation after Inner
            // Guard's death event, before the asynchronous BOSS activation.
            // This exercises the real boss-manager admission rejection without
            // changing production code or ever creating a second durable run.
            releaseReservations(runId);
            getLogger().info("E2E_FAULT_BOSS_RESERVATION_REVOKED run=" + runId);
        }
    }

    private void releaseReservations(UUID runId) {
        try {
            Object manager = readField(core, "stoneVaultManager");
            Object registry = readField(core, "encounterReservations");
            @SuppressWarnings("unchecked")
            Map<UUID, ?> sessions = (Map<UUID, ?>) readField(manager, "sessions");
            Object session = sessions.get(runId);
            if (session == null) throw new IllegalStateException("No active session for " + runId);
            @SuppressWarnings("unchecked")
            Collection<UUID> participants = (Collection<UUID>) readField(session, "participants");
            Method release = registry.getClass().getMethod("release", Collection.class);
            release.invoke(registry, participants);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("E2E reservation fault injection failed", exception);
        }
    }

    private void replayCallback(EntityDeathEvent event) {
        try {
            Object manager = readField(core, "stoneVaultManager");
            Method method = manager.getClass().getMethod("onActivityMobDeath", EntityDeathEvent.class);
            method.invoke(manager, event);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("E2E callback replay failed", exception);
        }
    }

    private static Object readField(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
