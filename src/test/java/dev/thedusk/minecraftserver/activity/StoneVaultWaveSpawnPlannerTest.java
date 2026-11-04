package dev.thedusk.minecraftserver.activity;

import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoneVaultWaveSpawnPlannerTest {
    @Test
    void levelArenaKeepsFourDistinctSupportedRingLocations() {
        var cells = StoneVaultWaveSpawnPlanner.select(
                0, 76, 0, 4, 8.1, 18.0,
                (x, y, z) -> y == 76
        );
        assertEquals(4, cells.size());
        assertEquals(4, new HashSet<>(cells).size());
        assertTrue(cells.stream().allMatch(cell -> cell.y() == 76));
        assertTrue(cells.stream().allMatch(cell ->
                cell.x() * cell.x() + cell.z() * cell.z() <= 144));
    }

    @Test
    void cliffDropIsAvoidedByFallingBackToContinuousSupportedPlatform() {
        // An 8-block ring would drop into an inaccessible ravine. The
        // center's 4-block plateau still has four safe cardinal locations.
        var cells = StoneVaultWaveSpawnPlanner.select(
                0, 76, 0, 4, 8.1, 18.0,
                (x, y, z) -> y == 76 && x * x + z * z <= 16
        );
        assertEquals(4, cells.size());
        assertTrue(cells.stream().allMatch(cell -> cell.y() == 76));
        assertTrue(cells.stream().allMatch(cell ->
                cell.x() * cell.x() + cell.z() * cell.z() <= 16));
    }

    @Test
    void unsafeOrIsolatedArenaFailsBeforePartialWaveSpawns() {
        var terrain = (StoneVaultWaveSpawnPlanner.StandingCell)
                (x, y, z) -> y == 76 && x * x + z * z <= 1;
        var failure = assertThrows(IllegalStateException.class, () ->
                StoneVaultWaveSpawnPlanner.select(
                        0, 76, 0, 4, 8.1, 18.0, terrain
                ));
        assertTrue(failure.getMessage().contains("No reachable supported"));
    }

    @Test
    void unsupportedCenterFailsClosed() {
        assertThrows(IllegalStateException.class, () ->
                StoneVaultWaveSpawnPlanner.select(
                        0, 76, 0, 4, 8.1, 18.0, (x, y, z) -> false
                ));
    }

    @Test
    void cannotSelectPositionsAcrossUnwalkableTerrainGap() {
        var cells = StoneVaultWaveSpawnPlanner.select(
                0, 76, 0, 4, 8.1, 18.0,
                (x, y, z) -> y == 76 && (x <= 0 || x >= 6)
        );
        assertEquals(4, cells.size());
        assertTrue(cells.stream().allMatch(cell -> cell.x() <= 0));
    }
}
