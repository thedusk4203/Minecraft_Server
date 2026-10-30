package dev.thedusk.minecraftserver.permissions;

import java.util.Objects;
import org.bukkit.command.CommandSender;

public final class CorePermissions {
    public static final String ADMIN = "minecraftserver.admin";

    private CorePermissions() {
    }

    public static boolean canAdmin(CommandSender sender) {
        Objects.requireNonNull(sender, "sender");
        return sender.hasPermission(ADMIN);
    }
}
