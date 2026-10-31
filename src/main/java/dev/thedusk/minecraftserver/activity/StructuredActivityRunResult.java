package dev.thedusk.minecraftserver.activity;

import java.util.Objects;

public record StructuredActivityRunResult(
        StructuredActivityRun run,
        boolean idempotentReplay
) {
    public StructuredActivityRunResult {
        Objects.requireNonNull(run, "run");
    }
}
