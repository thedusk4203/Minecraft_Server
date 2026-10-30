package dev.thedusk.minecraftserver.encounter;

public enum EncounterRunStatus {
    ACTIVE,
    COMPLETED,
    FAILED,
    ABANDONED;

    public boolean terminal() {
        return this != ACTIVE;
    }
}
