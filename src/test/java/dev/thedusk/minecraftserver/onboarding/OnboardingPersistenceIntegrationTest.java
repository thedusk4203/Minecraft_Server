package dev.thedusk.minecraftserver.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thedusk.minecraftserver.identity.PlayerIdentity;
import dev.thedusk.minecraftserver.identity.PlayerIdentityRepository;
import dev.thedusk.minecraftserver.persistence.DatabaseManager;
import dev.thedusk.minecraftserver.persistence.DatabaseSettings;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
@Testcontainers
class OnboardingPersistenceIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("minecraft_onboarding_test")
            .withUsername("minecraft_test")
            .withPassword("minecraft_test_password");

    private static DatabaseManager database;
    private static PlayerIdentityRepository identities;
    private static OnboardingProgressRepository onboarding;

    @BeforeAll
    static void openDatabase() {
        openServices();
    }

    @AfterAll
    static void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @BeforeEach
    void clearState() throws Exception {
        try (var connection = database.connection();
             var statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE player_onboarding, player_identity CASCADE");
        }
    }

    @Test
    void startingOnboardingIsIdempotent() throws Exception {
        PlayerIdentity player = identities.recordSeen(UUID.randomUUID(), "Starter");

        assertTrue(onboarding.ensureStarted(player.playerId()));
        assertFalse(onboarding.ensureStarted(player.playerId()));
        assertEquals(1L, countRows("player_onboarding"));

        OnboardingProgress progress = onboarding.find(player.playerId()).orElseThrow();
        assertNotNull(progress.startedAt());
        assertFalse(progress.landClaimed());
    }

    @Test
    void firstLandClaimIsRecordedExactlyOnce() {
        PlayerIdentity player = identities.recordSeen(UUID.randomUUID(), "Claimer");

        assertTrue(onboarding.markLandClaimed(player.playerId()));
        OnboardingProgress first = onboarding.find(player.playerId()).orElseThrow();
        assertNotNull(first.landClaimedAt());

        assertFalse(onboarding.markLandClaimed(player.playerId()));
        OnboardingProgress replay = onboarding.find(player.playerId()).orElseThrow();
        assertEquals(first.landClaimedAt(), replay.landClaimedAt());
    }

    @Test
    void restartPreservesLandClaimMilestone() {
        UUID minecraftUuid = UUID.randomUUID();
        PlayerIdentity beforeRestart = identities.recordSeen(minecraftUuid, "RestartClaim");
        onboarding.ensureStarted(beforeRestart.playerId());
        onboarding.markLandClaimed(beforeRestart.playerId());

        database.close();
        openServices();

        PlayerIdentity afterRestart = identities.findByMinecraftUuid(minecraftUuid).orElseThrow();
        OnboardingProgress progress = onboarding.find(afterRestart.playerId()).orElseThrow();
        assertEquals(beforeRestart.playerId(), afterRestart.playerId());
        assertTrue(progress.landClaimed());
        assertNotNull(progress.landClaimedAt());
    }

    private static void openServices() {
        database = DatabaseManager.open(new DatabaseSettings(
                POSTGRES.getHost(),
                POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                POSTGRES.getDatabaseName(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                4
        ));
        identities = new PlayerIdentityRepository(database);
        onboarding = new OnboardingProgressRepository(database);
    }

    private static long countRows(String table) throws Exception {
        try (var connection = database.connection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getLong(1);
        }
    }
}
