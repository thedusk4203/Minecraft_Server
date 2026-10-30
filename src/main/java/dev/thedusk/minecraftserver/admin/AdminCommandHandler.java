package dev.thedusk.minecraftserver.admin;

import dev.thedusk.minecraftserver.MinecraftServerCore;
import dev.thedusk.minecraftserver.permissions.CorePermissions;
import dev.thedusk.minecraftserver.persistence.DatabaseManager;
import dev.thedusk.minecraftserver.persistence.PersistenceExecutor;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class AdminCommandHandler implements CommandExecutor {
    private final MinecraftServerCore plugin;
    private final DatabaseManager database;
    private final PersistenceExecutor persistenceExecutor;
    private final AdminDiagnosticsService diagnostics;

    public AdminCommandHandler(
            MinecraftServerCore plugin,
            DatabaseManager database,
            PersistenceExecutor persistenceExecutor,
            AdminDiagnosticsService diagnostics
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.database = Objects.requireNonNull(database, "database");
        this.persistenceExecutor = Objects.requireNonNull(persistenceExecutor, "persistenceExecutor");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!CorePermissions.canAdmin(sender)) {
            sender.sendMessage("You do not have permission to use this command.");
            return true;
        }

        if (args.length != 1) {
            sendUsage(sender, label);
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        UUID actorMinecraftUuid = sender instanceof Player player ? player.getUniqueId() : null;
        UUID correlationId = UUID.randomUUID();

        return switch (subcommand) {
            case "health" -> {
                sendHealth(sender, correlationId);
                auditHealth(actorMinecraftUuid, correlationId);
                yield true;
            }
            case "diagnostics" -> {
                collectDiagnostics(sender, actorMinecraftUuid, correlationId);
                yield true;
            }
            default -> {
                sendUsage(sender, label);
                yield true;
            }
        };
    }

    private void sendHealth(CommandSender sender, UUID correlationId) {
        boolean databaseOpen = database.isOpen();
        String pool = databaseOpen
                ? database.activeConnections() + "/" + database.idleConnections()
                : "closed";

        sender.sendMessage(
                "MinecraftServerCore OK | Minecraft "
                        + plugin.getServer().getMinecraftVersion()
                        + " | online="
                        + plugin.getServer().getOnlinePlayers().size()
                        + " | db="
                        + (databaseOpen ? "UP" : "DOWN")
                        + " | pool(active/idle)="
                        + pool
                        + " | correlation="
                        + correlationId
        );
    }

    private void auditHealth(UUID actorMinecraftUuid, UUID correlationId) {
        persistenceExecutor
                .run(() -> diagnostics.auditCommand(actorMinecraftUuid, "health", correlationId))
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        plugin.getLogger().log(
                                Level.WARNING,
                                "Failed to audit health command; correlation=" + correlationId,
                                failure
                        );
                    }
                });
    }

    private void collectDiagnostics(
            CommandSender sender,
            UUID actorMinecraftUuid,
            UUID correlationId
    ) {
        persistenceExecutor
                .submit(() -> diagnostics.collect(actorMinecraftUuid, correlationId))
                .whenComplete((snapshot, failure) -> completeDiagnostics(
                        sender,
                        snapshot,
                        failure,
                        correlationId
                ));
    }

    private void completeDiagnostics(
            CommandSender sender,
            AdminDiagnosticsSnapshot snapshot,
            Throwable failure,
            UUID correlationId
    ) {
        if (!plugin.isEnabled()) {
            return;
        }

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (failure != null) {
                plugin.getLogger().log(
                        Level.SEVERE,
                        "Administrative diagnostics failed; correlation=" + correlationId,
                        failure
                );
                sender.sendMessage(
                        "MinecraftServerCore diagnostics FAILED | correlation=" + correlationId
                );
                return;
            }

            sender.sendMessage(
                    "MinecraftServerCore diagnostics | Minecraft "
                            + plugin.getServer().getMinecraftVersion()
                            + " | online="
                            + plugin.getServer().getOnlinePlayers().size()
                            + " | knownPlayers="
                            + snapshot.knownPlayers()
                            + " | economyAccounts="
                            + snapshot.economyAccounts()
                            + " | economyTx="
                            + snapshot.economyTransactions()
                            + " | auditEvents="
                            + snapshot.auditEvents()
                            + " | pool(active/idle)="
                            + snapshot.activeConnections()
                            + "/"
                            + snapshot.idleConnections()
                            + " | correlation="
                            + correlationId
            );
        });
    }

    private static void sendUsage(CommandSender sender, String label) {
        sender.sendMessage("Usage: /" + label + " <health|diagnostics>");
    }
}
