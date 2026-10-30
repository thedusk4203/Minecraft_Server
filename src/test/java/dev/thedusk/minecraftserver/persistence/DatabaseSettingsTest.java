package dev.thedusk.minecraftserver.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DatabaseSettingsTest {

    @Test
    void readsExplicitEnvironment() {
        Map<String, String> environment = Map.of(
                "DATABASE_HOST", "db.internal",
                "DATABASE_PORT", "5544",
                "DATABASE_NAME", "server_test",
                "DATABASE_USER", "server_user",
                "DATABASE_PASSWORD", "secret",
                "DATABASE_POOL_SIZE", "7"
        );

        DatabaseSettings settings = DatabaseSettings.fromEnvironment(environment);

        assertEquals("db.internal", settings.host());
        assertEquals(5544, settings.port());
        assertEquals("server_test", settings.database());
        assertEquals("server_user", settings.username());
        assertEquals("secret", settings.password());
        assertEquals(7, settings.maximumPoolSize());
        assertEquals("jdbc:postgresql://db.internal:5544/server_test", settings.jdbcUrl());
    }

    @Test
    void appliesSafeDevelopmentDefaults() {
        DatabaseSettings settings = DatabaseSettings.fromEnvironment(
                Map.of("DATABASE_PASSWORD", "secret")
        );

        assertEquals("localhost", settings.host());
        assertEquals(5432, settings.port());
        assertEquals("minecraft", settings.database());
        assertEquals("minecraft", settings.username());
        assertEquals(10, settings.maximumPoolSize());
    }

    @Test
    void rejectsMissingPassword() {
        assertThrows(
                IllegalStateException.class,
                () -> DatabaseSettings.fromEnvironment(Map.of())
        );
    }

    @Test
    void rejectsInvalidNumericSettings() {
        Map<String, String> environment = new HashMap<>();
        environment.put("DATABASE_PASSWORD", "secret");
        environment.put("DATABASE_PORT", "not-a-number");

        assertThrows(
                IllegalArgumentException.class,
                () -> DatabaseSettings.fromEnvironment(environment)
        );
    }
}
