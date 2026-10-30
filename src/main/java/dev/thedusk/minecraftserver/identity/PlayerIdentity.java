package dev.thedusk.minecraftserver.identity;

import java.util.UUID;

public record PlayerIdentity(
        UUID playerId,
        UUID minecraftUuid,
        String lastKnownName,
        IdentityPlatform platform,
        String platformSubject
) {
}
