package dev.thedusk.minecraftserver.admin;

public record AdminDiagnosticsSnapshot(
        long knownPlayers,
        long economyAccounts,
        long economyTransactions,
        long auditEvents,
        int activeConnections,
        int idleConnections
) {
}
