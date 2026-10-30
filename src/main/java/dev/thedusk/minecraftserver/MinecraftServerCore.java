package dev.thedusk.minecraftserver;

import java.util.Objects;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class MinecraftServerCore extends JavaPlugin {

    @Override
    public void onEnable() {
        PluginCommand command = Objects.requireNonNull(
                getCommand("mcserver"),
                "mcserver command must be declared in plugin.yml"
        );

        command.setExecutor((sender, ignoredCommand, ignoredLabel, args) -> {
            if (args.length == 1 && args[0].equalsIgnoreCase("health")) {
                sender.sendMessage(
                        "MinecraftServerCore OK | Minecraft "
                                + getServer().getMinecraftVersion()
                                + " | players="
                                + getServer().getOnlinePlayers().size()
                );
                return true;
            }

            sender.sendMessage("Usage: /mcserver health");
            return true;
        });

        getLogger().info(
                "MinecraftServerCore enabled on Minecraft " + getServer().getMinecraftVersion()
        );
    }

    @Override
    public void onDisable() {
        getLogger().info("MinecraftServerCore disabled");
    }
}
