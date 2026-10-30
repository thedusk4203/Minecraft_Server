package dev.thedusk.minecraftserver.reward;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class StoneWardenItemRewardCodec {
    private final NamespacedKey deliveryTokenKey;
    private final Component displayName = Component.text(StoneWardenItemRewardDefinition.DISPLAY_NAME);

    public StoneWardenItemRewardCodec(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        this.deliveryTokenKey = new NamespacedKey(plugin, "stone_warden_reward_token");
    }

    public ItemStack createMarked(UUID deliveryToken) {
        Objects.requireNonNull(deliveryToken, "deliveryToken");
        ItemStack item = new ItemStack(
                Material.valueOf(StoneWardenItemRewardDefinition.MATERIAL),
                StoneWardenItemRewardDefinition.AMOUNT
        );
        var meta = item.getItemMeta();
        meta.displayName(displayName);
        meta.getPersistentDataContainer().set(
                deliveryTokenKey,
                PersistentDataType.STRING,
                deliveryToken.toString()
        );
        item.setItemMeta(meta);
        return item;
    }

    public Optional<UUID> deliveryToken(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || item.getAmount() <= 0) {
            return Optional.empty();
        }
        String raw = item.getItemMeta().getPersistentDataContainer().get(
                deliveryTokenKey,
                PersistentDataType.STRING
        );
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    public boolean isMarked(ItemStack item) {
        return deliveryToken(item).isPresent();
    }

    public boolean isExpectedMarkedItem(ItemStack item, UUID deliveryToken) {
        if (item == null
                || item.getType() != Material.valueOf(StoneWardenItemRewardDefinition.MATERIAL)
                || item.getAmount() != StoneWardenItemRewardDefinition.AMOUNT
                || deliveryToken(item).filter(deliveryToken::equals).isEmpty()) {
            return false;
        }
        return Objects.equals(item.getItemMeta().displayName(), displayName);
    }

    public ItemStack strip(ItemStack source) {
        Objects.requireNonNull(source, "source");
        ItemStack clean = source.clone();
        var meta = clean.getItemMeta();
        meta.getPersistentDataContainer().remove(deliveryTokenKey);
        clean.setItemMeta(meta);
        return clean;
    }

    public byte[] serialize(ItemStack item) {
        Objects.requireNonNull(item, "item");
        return item.ensureServerConversions().serializeAsBytes();
    }

    public ItemStack deserialize(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        return ItemStack.deserializeBytes(payload);
    }

    public String sha256(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available in the JVM", exception);
        }
    }
}
