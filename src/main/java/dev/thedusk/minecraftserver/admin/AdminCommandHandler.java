package dev.thedusk.minecraftserver.admin;

import dev.thedusk.minecraftserver.MinecraftServerCore;
import dev.thedusk.minecraftserver.permissions.CorePermissions;
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
    private final PersistenceExecutor persistenceExecutor;
    private final AdminDiagnosticsService diagnostics;

    public AdminCommandHandler(
            MinecraftServerCore plugin,
            PersistenceExecutor persistenceExecutor,
            AdminDiagnosticsService diagnostics
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
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
        if (!subcommand.equals("health") && !subcommand.equals("diagnostics")) {
            sendUsage(sender, label);
            return true;
        }

        UUID actorMinecraftUuid = sender instanceof Player player ? player.getUniqueId() : null;
        UUID correlationId = UUID.randomUUID();

        persistenceExecutor
                .submit(() -> diagnostics.collect(actorMinecraftUuid, subcommand, correlationId))
                .whenComplete((snapshot, failure) -> complete(sender, snapshot, failure, correlationId));
        return true;
    }

    private void complete(
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
                    "MinecraftServerCore OK | Minecraft "
                            + plugin.getServer().getMinecraftVersion()
                            + " | online="
                            + plugin.getServer().getOnlinePlayers().size()
                            + " | db=UP"
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
