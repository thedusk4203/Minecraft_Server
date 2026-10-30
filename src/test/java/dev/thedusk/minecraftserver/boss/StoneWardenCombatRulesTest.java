package dev.thedusk.minecraftserver.boss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StoneWardenCombatRulesTest {
    @Test
    void scalesHealthByPartySize() {
        assertEquals(160.0, StoneWardenCombatRules.maxHealth(1));
        assertEquals(200.0, StoneWardenCombatRules.maxHealth(2));
        assertEquals(240.0, StoneWardenCombatRules.maxHealth(3));
        assertEquals(280.0, StoneWardenCombatRules.maxHealth(4));
        assertThrows(IllegalArgumentException.class, () -> StoneWardenCombatRules.maxHealth(0));
        assertThrows(IllegalArgumentException.class, () -> StoneWardenCombatRules.maxHealth(5));
    }

    @Test
    void checksArenaRadiusAndVerticalTolerance() {
        assertTrue(StoneWardenCombatRules.insideRadius2d(0, 0, 3, 4, 5));
        assertFalse(StoneWardenCombatRules.insideRadius2d(0, 0, 5.01, 0, 5));

        assertTrue(StoneWardenCombatRules.insideArena(0, 64, 0, 3, 72, 4, 5, 8));
        assertFalse(StoneWardenCombatRules.insideArena(0, 64, 0, 3, 72.01, 4, 5, 8));
        assertFalse(StoneWardenCombatRules.insideArena(0, 64, 0, 6, 64, 0, 5, 8));
    }

    @Test
    void computesDistanceToChargeSegment() {
        assertEquals(4.0, StoneWardenCombatRules.distanceSquaredToSegment2d(
                5, 2, 0, 0, 10, 0
        ), 1e-9);
        assertEquals(25.0, StoneWardenCombatRules.distanceSquaredToSegment2d(
                -5, 0, 0, 0, 10, 0
        ), 1e-9);
        assertEquals(8.0, StoneWardenCombatRules.distanceSquaredToSegment2d(
                2, 2, 0, 0, 0, 0
        ), 1e-9);
    }
}
