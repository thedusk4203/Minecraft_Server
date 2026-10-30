package dev.thedusk.minecraftserver.admin;

public record AdminDiagnosticsSnapshot(
        long knownPlayers,
        long economyAccounts,
        long economyTransactions,
        long auditEvents,
        long encounterRuns,
        long activeEncounterRuns,
        long encounterRewards,
        long pendingEncounterRewards,
        long encounterRewardItems,
        long pendingEncounterRewardItems,
        int activeConnections,
        int idleConnections
) {
}
