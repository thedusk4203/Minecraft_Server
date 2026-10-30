package dev.thedusk.minecraftserver.boss;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record BossDefinition(
        String id,
        String encounterId,
        String entityType,
        String displayName,
        double joinRadius,
        double arenaRadius,
        int slamIntervalTicks,
        int slamWindupTicks,
        double slamRadius,
        double slamDamage,
        int ringIntervalTicks,
        int ringWindupTicks,
        double ringSafeRadius,
        double ringOuterRadius,
        double ringDamage
) {
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9_.-]{0,95}$");
    private static final Pattern ENTITY_TYPE_PATTERN = Pattern.compile("^[A-Z0-9_]{2,64}$");

    public BossDefinition {
        id = normalizeId(id, "boss id");
        encounterId = normalizeId(encounterId, "encounter id");
        Objects.requireNonNull(entityType, "entityType");
        entityType = entityType.trim().toUpperCase(Locale.ROOT);
        if (!ENTITY_TYPE_PATTERN.matcher(entityType).matches()) {
            throw new IllegalArgumentException("entityType must be an uppercase Bukkit EntityType name");
        }
        Objects.requireNonNull(displayName, "displayName");
        displayName = displayName.trim();
        if (displayName.isEmpty() || displayName.length() > 64) {
            throw new IllegalArgumentException("displayName must contain 1-64 characters");
        }
        requireRange(joinRadius, 1.0, 64.0, "joinRadius");
        requireRange(arenaRadius, 4.0, 96.0, "arenaRadius");
        if (joinRadius > arenaRadius) {
            throw new IllegalArgumentException("joinRadius must not exceed arenaRadius");
        }
        requireTicks(slamIntervalTicks, 20, 1_200, "slamIntervalTicks");
        requireTicks(slamWindupTicks, 5, 200, "slamWindupTicks");
        if (slamWindupTicks >= slamIntervalTicks) {
            throw new IllegalArgumentException("slamWindupTicks must be shorter than slamIntervalTicks");
        }
        requireRange(slamRadius, 1.0, arenaRadius, "slamRadius");
        requireRange(slamDamage, 0.5, 40.0, "slamDamage");
        requireTicks(ringIntervalTicks, 20, 1_200, "ringIntervalTicks");
        requireTicks(ringWindupTicks, 5, 200, "ringWindupTicks");
        if (ringWindupTicks >= ringIntervalTicks) {
            throw new IllegalArgumentException("ringWindupTicks must be shorter than ringIntervalTicks");
        }
        requireRange(ringSafeRadius, 0.5, arenaRadius, "ringSafeRadius");
        requireRange(ringOuterRadius, ringSafeRadius + 0.5, arenaRadius, "ringOuterRadius");
        requireRange(ringDamage, 0.5, 40.0, "ringDamage");
    }

    private static String normalizeId(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must match " + ID_PATTERN.pattern());
        }
        return normalized;
    }

    private static void requireTicks(int value, int minimum, int maximum, String field) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(field + " must be between " + minimum + " and " + maximum);
        }
    }

    private static void requireRange(double value, double minimum, double maximum, String field) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(field + " must be between " + minimum + " and " + maximum);
        }
    }
}
