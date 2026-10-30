package dev.thedusk.minecraftserver.encounter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record EncounterDefinition(
        String id,
        int version,
        int minPlayers,
        int maxPlayers,
        int timeoutSeconds
) {
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9_.-]{0,95}$");
    private static final int MAX_PARTY_SIZE = 32;
    private static final int MIN_TIMEOUT_SECONDS = 10;
    private static final int MAX_TIMEOUT_SECONDS = 7_200;

    public EncounterDefinition {
        Objects.requireNonNull(id, "id");
        id = id.trim().toLowerCase(Locale.ROOT);
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "encounter id must match " + ID_PATTERN.pattern()
            );
        }
        if (version < 1) {
            throw new IllegalArgumentException("encounter version must be at least 1");
        }
        if (minPlayers < 1 || minPlayers > MAX_PARTY_SIZE) {
            throw new IllegalArgumentException("minPlayers must be between 1 and " + MAX_PARTY_SIZE);
        }
        if (maxPlayers < minPlayers || maxPlayers > MAX_PARTY_SIZE) {
            throw new IllegalArgumentException(
                    "maxPlayers must be between minPlayers and " + MAX_PARTY_SIZE
            );
        }
        if (timeoutSeconds < MIN_TIMEOUT_SECONDS || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException(
                    "timeoutSeconds must be between "
                            + MIN_TIMEOUT_SECONDS + " and " + MAX_TIMEOUT_SECONDS
            );
        }
    }

    public String definitionSha256() {
        String canonical = id + "\n"
                + version + "\n"
                + minPlayers + "\n"
                + maxPlayers + "\n"
                + timeoutSeconds + "\n";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM does not provide SHA-256", exception);
        }
    }
}
