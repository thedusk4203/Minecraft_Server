package dev.thedusk.minecraftserver.onboarding;

import dev.thedusk.minecraftserver.economy.EconomyLedger;
import dev.thedusk.minecraftserver.economy.EconomyMutation;
import dev.thedusk.minecraftserver.economy.EconomyResult;
import dev.thedusk.minecraftserver.persistence.DatabaseManager;
import dev.thedusk.minecraftserver.persistence.PersistenceException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class FirstSkillCurrencyService {
    public static final String CURRENCY_CODE = "COINS";
    public static final long STARTER_REWARD = 100L;

    private static final Pattern SKILL_ID_PATTERN = Pattern.compile("[a-z0-9_.:/-]{1,128}");

    private final DatabaseManager database;
    private final OnboardingProgressRepository onboarding;
    private final EconomyLedger economy;

    public FirstSkillCurrencyService(
            DatabaseManager database,
            OnboardingProgressRepository onboarding,
            EconomyLedger economy
    ) {
        this.database = Objects.requireNonNull(database, "database");
        this.onboarding = Objects.requireNonNull(onboarding, "onboarding");
        this.economy = Objects.requireNonNull(economy, "economy");
    }

    public FirstSkillCurrencyResult recordLevelUp(
            UUID playerId,
            String skillId,
            int level,
            UUID correlationId
    ) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(correlationId, "correlationId");
        String normalizedSkillId = normalizeSkillId(skillId);
        if (level < 1) {
            throw new IllegalArgumentException("level must be at least 1");
        }

        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                onboarding.ensureStarted(connection, playerId);
                OnboardingProgress progress = onboarding.lock(connection, playerId);

                boolean firstSkillRecorded = false;
                if (!progress.firstSkillLeveled()) {
                    firstSkillRecorded = onboarding.markFirstSkill(
                            connection,
                            playerId,
                            normalizedSkillId,
                            level
                    );
                }

                boolean currencyGranted = false;
                long balanceAfter = -1L;
                if (!progress.firstCurrencyEarned()) {
                    EconomyResult economyResult = economy.applyInTransaction(
                            connection,
                            new EconomyMutation(
                                    playerId,
                                    CURRENCY_CODE,
                                    STARTER_REWARD,
                                    "onboarding.skill_level",
                                    "onboarding:first-currency:" + playerId,
                                    correlationId,
                                    "{\"skillId\":\"" + normalizedSkillId
                                            + "\",\"skillLevel\":" + level + "}"
                            )
                    );
                    if (!onboarding.markFirstCurrencyEarned(connection, playerId)) {
                        throw new IllegalStateException(
                                "First-currency milestone changed unexpectedly for player " + playerId
                        );
                    }
                    currencyGranted = !economyResult.idempotentReplay();
                    balanceAfter = economyResult.balanceAfter();
                }

                connection.commit();
                return new FirstSkillCurrencyResult(
                        firstSkillRecorded,
                        currencyGranted,
                        balanceAfter,
                        normalizedSkillId,
                        level
                );
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new PersistenceException(
                    "Failed to record first skill/currency milestone for player " + playerId,
                    exception
            );
        }
    }

    private static String normalizeSkillId(String value) {
        Objects.requireNonNull(value, "skillId");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!SKILL_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("skillId contains unsupported characters");
        }
        return normalized;
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
