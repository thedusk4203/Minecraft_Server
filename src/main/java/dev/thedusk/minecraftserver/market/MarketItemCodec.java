package dev.thedusk.minecraftserver.market;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class MarketItemCodec {
    private final NamespacedKey escrowTokenKey;

    public MarketItemCodec(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        this.escrowTokenKey = new NamespacedKey(plugin, "market_escrow_token");
    }

    public ItemStack mark(ItemStack source, UUID escrowToken) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(escrowToken, "escrowToken");
        ItemStack marked = source.clone();
        var meta = marked.getItemMeta();
        meta.getPersistentDataContainer().set(
                escrowTokenKey,
                PersistentDataType.STRING,
                escrowToken.toString()
        );
        marked.setItemMeta(meta);
        return marked;
    }

    public Optional<UUID> escrowToken(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || item.getAmount() <= 0) {
            return Optional.empty();
        }
        var meta = item.getItemMeta();
        String raw = meta.getPersistentDataContainer().get(escrowTokenKey, PersistentDataType.STRING);
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
        return escrowToken(item).isPresent();
    }

    public ItemStack strip(ItemStack source) {
        Objects.requireNonNull(source, "source");
        ItemStack clean = source.clone();
        var meta = clean.getItemMeta();
        meta.getPersistentDataContainer().remove(escrowTokenKey);
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
