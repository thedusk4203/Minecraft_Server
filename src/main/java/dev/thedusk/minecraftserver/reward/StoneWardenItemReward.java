package dev.thedusk.minecraftserver.reward;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public record StoneWardenItemReward(
        UUID rewardId,
        UUID playerId,
        String itemKey,
        int itemVersion,
        UUID deliveryToken,
        StoneWardenItemRewardStatus status,
        String itemMaterial,
        int itemAmount,
        byte[] itemPayload,
        String itemSha256
) {
    public StoneWardenItemReward {
        Objects.requireNonNull(rewardId, "rewardId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(itemKey, "itemKey");
        Objects.requireNonNull(deliveryToken, "deliveryToken");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(itemMaterial, "itemMaterial");
        itemPayload = itemPayload == null ? null : Arrays.copyOf(itemPayload, itemPayload.length);
    }

    @Override
    public byte[] itemPayload() {
        return itemPayload == null ? null : Arrays.copyOf(itemPayload, itemPayload.length);
    }
}
