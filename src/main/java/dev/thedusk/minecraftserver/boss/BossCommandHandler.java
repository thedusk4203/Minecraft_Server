package dev.thedusk.minecraftserver.boss;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class BossCommandHandler implements CommandExecutor {
    private final StoneWardenBossManager bossManager;

    public BossCommandHandler(StoneWardenBossManager bossManager) {
        this.bossManager = Objects.requireNonNull(bossManager, "bossManager");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /boss <start [player...]|status>");
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> {
                sender.sendMessage(bossManager.status());
                yield true;
            }
            case "start" -> start(sender, args);
            default -> {
                sender.sendMessage("Usage: /boss <start [player...]|status>");
                yield true;
            }
        };
    }

    private boolean start(CommandSender sender, String[] args) {
        if (!(sender instanceof Player leader)) {
            sender.sendMessage("Starting a boss requires an online player.");
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
            bossManager.start(leader, participants);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage("§c" + exception.getMessage());
        }
        return true;
    }
}
