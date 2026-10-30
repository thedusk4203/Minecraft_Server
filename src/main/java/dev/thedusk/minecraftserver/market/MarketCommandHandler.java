package dev.thedusk.minecraftserver.market;

import java.util.Locale;
import java.util.Objects;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class MarketCommandHandler implements CommandExecutor {
    private final MarketEscrowService escrow;
    private final MarketPurchaseService purchases;

    public MarketCommandHandler(MarketEscrowService escrow, MarketPurchaseService purchases) {
        this.escrow = Objects.requireNonNull(escrow, "escrow");
        this.purchases = Objects.requireNonNull(purchases, "purchases");
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This market command currently requires an online player.");
            return true;
        }
        if (args.length == 0) {
            usage(player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "sell" -> sell(player, args);
            case "list" -> escrow.list(player);
            case "buy" -> buy(player, args);
            case "cancel" -> cancel(player, args);
            case "recover" -> {
                escrow.recover(player);
                purchases.recover(player);
                player.sendMessage("§7Đang kiểm tra escrow và delivery cần khôi phục...");
            }
            default -> usage(player);
        }
        return true;
    }

    private void sell(Player player, String[] args) {
        if (args.length != 2) {
            player.sendMessage("§eCách dùng: /market sell <giá COINS>");
            return;
        }
        Long price = positiveLong(args[1]);
        if (price == null) {
            player.sendMessage("§cGiá phải là số nguyên dương.");
            return;
        }
        escrow.sell(player, price);
    }

    private void buy(Player player, String[] args) {
        if (args.length != 2) {
            player.sendMessage("§eCách dùng: /market buy <listing #>");
            return;
        }
        Long listingNumber = listingNumber(args[1]);
        if (listingNumber == null) {
            player.sendMessage("§cListing ID phải là số nguyên dương.");
            return;
        }
        purchases.buy(player, listingNumber);
    }

    private void cancel(Player player, String[] args) {
        if (args.length != 2) {
            player.sendMessage("§eCách dùng: /market cancel <listing #>");
            return;
        }
        Long listingNumber = listingNumber(args[1]);
        if (listingNumber == null) {
            player.sendMessage("§cListing ID phải là số nguyên dương.");
            return;
        }
        escrow.cancel(player, listingNumber);
    }

    private static Long listingNumber(String value) {
        return positiveLong(value.startsWith("#") ? value.substring(1) : value);
    }

    private static Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static void usage(Player player) {
        player.sendMessage("§6Market commands:");
        player.sendMessage("§e/market sell <giá> §7- đưa stack đang cầm vào escrow");
        player.sendMessage("§e/market list §7- xem tối đa 20 listing đang mở");
        player.sendMessage("§e/market buy <#> §7- mua listing và nhận item");
        player.sendMessage("§e/market cancel <#> §7- hủy listing của bạn và trả item");
        player.sendMessage("§e/market recover §7- tiếp tục escrow/delivery bị gián đoạn");
    }
}
