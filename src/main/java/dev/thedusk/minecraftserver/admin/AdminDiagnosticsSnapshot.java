package dev.thedusk.minecraftserver.admin;

public record AdminDiagnosticsSnapshot(
        long knownPlayers,
        long economyAccounts,
        long economyTransactions,
        long auditEvents,
        long encounterRuns,
        long activeEncounterRuns,
        int activeConnections,
        int idleConnections
) {
}
