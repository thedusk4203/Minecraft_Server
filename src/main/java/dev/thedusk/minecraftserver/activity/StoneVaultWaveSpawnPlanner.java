package dev.thedusk.minecraftserver.activity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Deterministic, terrain-aware selection for a complete Stone Vault wave. */
final class StoneVaultWaveSpawnPlanner {
    @FunctionalInterface
    interface StandingCell {
        boolean isSafe(int blockX, int feetY, int blockZ);
    }

    record Cell(int x, int y, int z) {}

    private record Candidate(Cell cell, double score) {}

    private StoneVaultWaveSpawnPlanner() {}

    static List<Cell> select(
            int centerX,
            int centerY,
            int centerZ,
            int count,
            double desiredRadius,
            double arenaRadius,
            StandingCell terrain
    ) {
        if (count <= 0 || count > 16 || desiredRadius < 2.0 || arenaRadius < 5.0) {
            throw new IllegalArgumentException("Invalid Stone Vault spawn-planner arguments");
        }
        Objects.requireNonNull(terrain, "terrain");
        int limit = (int) Math.floor(Math.min(12.0, arenaRadius - 2.0));
        if (!terrain.isSafe(centerX, centerY, centerZ)) {
            throw new IllegalStateException("Stone Vault leader has no supported arena center");
        }

        List<Cell> selected = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            double angle = 2.0 * Math.PI * index / count;
            double targetX = centerX + Math.cos(angle) * desiredRadius;
            double targetZ = centerZ + Math.sin(angle) * desiredRadius;
            List<Candidate> candidates = new ArrayList<>();

            for (int dx = -limit; dx <= limit; dx++) {
                for (int dz = -limit; dz <= limit; dz++) {
                    double distanceSquared = (double) dx * dx + (double) dz * dz;
                    if (distanceSquared < 6.25 || distanceSquared > (double) limit * limit) {
                        continue;
                    }
                    for (int deltaY : new int[]{0, -1, 1, -2, 2}) {
                        Cell cell = new Cell(centerX + dx, centerY + deltaY, centerZ + dz);
                        double targetError = Math.pow(cell.x() - targetX, 2)
                                + Math.pow(cell.z() - targetZ, 2);
                        candidates.add(new Candidate(cell, targetError + Math.abs(deltaY) * 6.0));
                    }
                }
            }
            candidates.sort(Comparator.comparingDouble(Candidate::score)
                    .thenComparingInt(c -> c.cell().x())
                    .thenComparingInt(c -> c.cell().z())
                    .thenComparingInt(c -> c.cell().y()));

            Cell accepted = null;
            for (Candidate candidate : candidates) {
                Cell cell = candidate.cell();
                if (!terrain.isSafe(cell.x(), cell.y(), cell.z())) {
                    continue;
                }
                boolean collides = selected.stream().anyMatch(existing -> {
                    int dx = existing.x() - cell.x();
                    int dz = existing.z() - cell.z();
                    return dx * dx + dz * dz < 4;
                });
                if (collides || !hasContinuousFooting(centerX, centerY, centerZ, cell, terrain)) {
                    continue;
                }
                accepted = cell;
                break;
            }
            if (accepted == null) {
                // Plan every position before world mutation; never leave a partial wave.
                throw new IllegalStateException("No reachable supported Stone Vault wave position "
                        + (index + 1) + "/" + count);
            }
            selected.add(accepted);
        }
        return List.copyOf(selected);
    }

    private static boolean hasContinuousFooting(
            int centerX,
            int centerY,
            int centerZ,
            Cell destination,
            StandingCell terrain
    ) {
        double dx = destination.x() - centerX;
        double dz = destination.z() - centerZ;
        int steps = Math.max(1, (int) Math.ceil(Math.hypot(dx, dz) * 2));
        for (int step = 1; step <= steps; step++) {
            double fraction = (double) step / steps;
            int x = (int) Math.round(centerX + fraction * dx);
            int y = (int) Math.round(centerY + fraction * (destination.y() - centerY));
            int z = (int) Math.round(centerZ + fraction * dz);
            if (!terrain.isSafe(x, y, z)) {
                return false;
            }
        }
        return true;
    }
}
