package dev.thedusk.minecraftserver.activity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record StructuredActivityDefinition(
        String id,
        int version,
        String encounterId,
        int encounterVersion,
        int minPlayers,
        int maxPlayers,
        int outerSealCount,
        int innerGuardMeleeCount,
        int innerGuardRangedCount,
        int arenaRadius
) {
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9_.-]{0,95}$");
    private static final int MAX_PARTY_SIZE = 32;
    private static final int MAX_WAVE_COUNT = 64;
    private static final int MIN_ARENA_RADIUS = 6;
    private static final int MAX_ARENA_RADIUS = 96;

    public StructuredActivityDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(encounterId, "encounterId");
        id = normalizeId(id, "activity id");
        encounterId = normalizeId(encounterId, "encounter id");

        if (version < 1) {
            throw new IllegalArgumentException("activity version must be at least 1");
        }
        if (encounterVersion < 1) {
            throw new IllegalArgumentException("encounterVersion must be at least 1");
        }
        if (minPlayers < 1 || minPlayers > MAX_PARTY_SIZE) {
            throw new IllegalArgumentException("minPlayers must be between 1 and " + MAX_PARTY_SIZE);
        }
        if (maxPlayers < minPlayers || maxPlayers > MAX_PARTY_SIZE) {
            throw new IllegalArgumentException(
                    "maxPlayers must be between minPlayers and " + MAX_PARTY_SIZE
            );
        }
        requireWaveCount(outerSealCount, "outerSealCount");
        requireWaveCount(innerGuardMeleeCount, "innerGuardMeleeCount");
        requireWaveCount(innerGuardRangedCount, "innerGuardRangedCount");
        if (arenaRadius < MIN_ARENA_RADIUS || arenaRadius > MAX_ARENA_RADIUS) {
            throw new IllegalArgumentException(
                    "arenaRadius must be between " + MIN_ARENA_RADIUS + " and " + MAX_ARENA_RADIUS
            );
        }
    }

    public int innerGuardCount() {
        return innerGuardMeleeCount + innerGuardRangedCount;
    }

    public String definitionSha256() {
        String canonical = id + "\n"
                + version + "\n"
                + encounterId + "\n"
                + encounterVersion + "\n"
                + minPlayers + "\n"
                + maxPlayers + "\n"
                + outerSealCount + "\n"
                + innerGuardMeleeCount + "\n"
                + innerGuardRangedCount + "\n"
                + arenaRadius + "\n";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM does not provide SHA-256", exception);
        }
    }

    private static String normalizeId(String value, String field) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must match " + ID_PATTERN.pattern());
        }
        return normalized;
    }

    private static void requireWaveCount(int value, String field) {
        if (value < 1 || value > MAX_WAVE_COUNT) {
            throw new IllegalArgumentException(field + " must be between 1 and " + MAX_WAVE_COUNT);
        }
    }
}
