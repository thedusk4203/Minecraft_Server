package dev.thedusk.minecraftserver.activity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class DungeonCommandHandler implements CommandExecutor {
    private final StoneVaultActivityManager stoneVault;

    public DungeonCommandHandler(StoneVaultActivityManager stoneVault) {
        this.stoneVault = Objects.requireNonNull(stoneVault, "stoneVault");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> {
                sender.sendMessage(stoneVault.status());
                yield true;
            }
            case "start" -> start(sender, args);
            default -> {
                sendUsage(sender);
                yield true;
            }
        };
    }

    private boolean start(CommandSender sender, String[] args) {
        if (!(sender instanceof Player leader)) {
            sender.sendMessage("Starting Stone Vault requires an online player.");
            return true;
        }

        List<Player> participants = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            Player player = Bukkit.getPlayerExact(args[i]);
            if (player == null) {
                sender.sendMessage("§cPlayer not online: " + args[i]);
                return true;
            }
            participants.add(player);
        }

        try {
            stoneVault.start(leader, participants);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage("§c" + exception.getMessage());
        }
        return true;
    }

    private static void sendUsage(CommandSender sender) {
        sender.sendMessage("Usage: /dungeon <start [player...]|status>");
    }
}
