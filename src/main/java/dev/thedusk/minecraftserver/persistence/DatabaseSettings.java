package dev.thedusk.minecraftserver.persistence;

import java.util.Map;
import java.util.Objects;

public record DatabaseSettings(
        String host,
        int port,
        String database,
        String username,
        String password,
        int maximumPoolSize
) {
    private static final int DEFAULT_POSTGRES_PORT = 5432;
    private static final int DEFAULT_POOL_SIZE = 10;

    public DatabaseSettings {
        host = requireNonBlank(host, "host");
        database = requireNonBlank(database, "database");
        username = requireNonBlank(username, "username");
        password = requireNonBlank(password, "password");

        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        if (maximumPoolSize < 1 || maximumPoolSize > 100) {
            throw new IllegalArgumentException("maximumPoolSize must be between 1 and 100");
        }
    }

    public static DatabaseSettings fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");

        return new DatabaseSettings(
                valueOrDefault(environment, "DATABASE_HOST", "localhost"),
                integerOrDefault(environment, "DATABASE_PORT", DEFAULT_POSTGRES_PORT),
                valueOrDefault(environment, "DATABASE_NAME", "minecraft"),
                valueOrDefault(environment, "DATABASE_USER", "minecraft"),
                required(environment, "DATABASE_PASSWORD"),
                integerOrDefault(environment, "DATABASE_POOL_SIZE", DEFAULT_POOL_SIZE)
        );
    }

    public String jdbcUrl() {
        return "jdbc:postgresql://" + host + ":" + port + "/" + database;
    }

    private static String valueOrDefault(Map<String, String> environment, String key, String fallback) {
        String value = environment.get(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int integerOrDefault(Map<String, String> environment, String key, int fallback) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be an integer", exception);
        }
    }

    private static String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is required");
        }
        return value;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
