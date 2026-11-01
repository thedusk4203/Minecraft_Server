package dev.thedusk.minecraftserver.boss;

import dev.thedusk.minecraftserver.reward.StoneWardenItemRewardService;
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
    private final StoneWardenItemRewardService itemRewards;

    public BossCommandHandler(
            StoneWardenBossManager bossManager,
            StoneWardenItemRewardService itemRewards
    ) {
        this.bossManager = Objects.requireNonNull(bossManager, "bossManager");
        this.itemRewards = Objects.requireNonNull(itemRewards, "itemRewards");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> {
                sender.sendMessage(bossManager.status());
                yield true;
            }
            case "recover" -> recover(sender);
            case "start" -> start(sender, args);
            default -> {
                sendUsage(sender);
                yield true;
            }
        };
    }

    private boolean recover(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Recovering a boss reward requires an online player.");
            return true;
        }
        itemRewards.recover(player, true);
        return true;
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

    private static void sendUsage(CommandSender sender) {
        sender.sendMessage("Usage: /boss <start [player...]|status|recover>");
    }
}
