package dev.thedusk.minecraftserver.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import org.flywaydb.core.Flyway;

public final class DatabaseManager implements AutoCloseable {
    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration VALIDATION_TIMEOUT = Duration.ofSeconds(2);
    private static final String POSTGRES_DRIVER = "org.postgresql.Driver";

    private final HikariDataSource dataSource;

    private DatabaseManager(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public static DatabaseManager open(DatabaseSettings settings) {
        Objects.requireNonNull(settings, "settings");

        HikariConfig config = new HikariConfig();
        config.setPoolName("minecraft-server-db");
        config.setDriverClassName(POSTGRES_DRIVER);
        config.setJdbcUrl(settings.jdbcUrl());
        config.setUsername(settings.username());
        config.setPassword(settings.password());
        config.setMaximumPoolSize(settings.maximumPoolSize());
        config.setMinimumIdle(1);
        config.setConnectionTimeout(CONNECTION_TIMEOUT.toMillis());
        config.setValidationTimeout(VALIDATION_TIMEOUT.toMillis());
        config.setAutoCommit(true);
        config.addDataSourceProperty("tcpKeepAlive", "true");
        config.addDataSourceProperty("ApplicationName", "minecraft-server-core");

        HikariDataSource dataSource = new HikariDataSource(config);
        DatabaseManager manager = new DatabaseManager(dataSource);
        try {
            manager.migrate();
            return manager;
        } catch (RuntimeException exception) {
            manager.close();
            throw exception;
        }
    }

    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    public boolean isHealthy() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid((int) VALIDATION_TIMEOUT.toSeconds());
        } catch (SQLException exception) {
            return false;
        }
    }

    public int activeConnections() {
        return dataSource.getHikariPoolMXBean().getActiveConnections();
    }

    public int idleConnections() {
        return dataSource.getHikariPoolMXBean().getIdleConnections();
    }

    private void migrate() {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
