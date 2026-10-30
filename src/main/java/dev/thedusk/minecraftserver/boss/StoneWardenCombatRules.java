package dev.thedusk.minecraftserver.boss;

public final class StoneWardenCombatRules {
    public static final double ARENA_RADIUS = 18.0;
    public static final double PARTICIPANT_RADIUS = 24.0;
    public static final double SLAM_RADIUS = 5.5;
    public static final double CHARGE_WIDTH = 1.5;
    public static final double BASE_HEALTH = 160.0;
    public static final double HEALTH_PER_EXTRA_PLAYER = 40.0;
    public static final double ENRAGE_HEALTH_FRACTION = 0.35;

    private StoneWardenCombatRules() {
    }

    public static double maxHealth(int participantCount) {
        if (participantCount < 1 || participantCount > 4) {
            throw new IllegalArgumentException("participantCount must be between 1 and 4");
        }
        return BASE_HEALTH + HEALTH_PER_EXTRA_PLAYER * (participantCount - 1);
    }

    public static boolean insideRadius2d(
            double centerX,
            double centerZ,
            double pointX,
            double pointZ,
            double radius
    ) {
        double dx = pointX - centerX;
        double dz = pointZ - centerZ;
        return dx * dx + dz * dz <= radius * radius;
    }

    public static double distanceSquaredToSegment2d(
            double pointX,
            double pointZ,
            double startX,
            double startZ,
            double endX,
            double endZ
    ) {
        double segmentX = endX - startX;
        double segmentZ = endZ - startZ;
        double lengthSquared = segmentX * segmentX + segmentZ * segmentZ;
        if (lengthSquared == 0.0) {
            double dx = pointX - startX;
            double dz = pointZ - startZ;
            return dx * dx + dz * dz;
        }

        double projection = ((pointX - startX) * segmentX + (pointZ - startZ) * segmentZ)
                / lengthSquared;
        double t = Math.max(0.0, Math.min(1.0, projection));
        double nearestX = startX + t * segmentX;
        double nearestZ = startZ + t * segmentZ;
        double dx = pointX - nearestX;
        double dz = pointZ - nearestZ;
        return dx * dx + dz * dz;
    }
}
