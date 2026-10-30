package dev.thedusk.minecraftserver.encounter;

import java.util.Objects;

public record EncounterRunResult(
        EncounterRun run,
        boolean idempotentReplay
) {
    public EncounterRunResult {
        Objects.requireNonNull(run, "run");
    }
}
