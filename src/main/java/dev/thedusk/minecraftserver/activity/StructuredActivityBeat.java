package dev.thedusk.minecraftserver.activity;

public enum StructuredActivityBeat {
    OUTER_SEAL(1),
    INNER_GUARD(2),
    BOSS(3);

    private final int ordinalValue;

    StructuredActivityBeat(int ordinalValue) {
        this.ordinalValue = ordinalValue;
    }

    public int ordinalValue() {
        return ordinalValue;
    }

    public StructuredActivityBeat next() {
        return switch (this) {
            case OUTER_SEAL -> INNER_GUARD;
            case INNER_GUARD -> BOSS;
            case BOSS -> null;
        };
    }

    public static StructuredActivityBeat fromOrdinalValue(int value) {
        for (StructuredActivityBeat beat : values()) {
            if (beat.ordinalValue == value) {
                return beat;
            }
        }
        throw new IllegalArgumentException("Unknown structured activity beat ordinal: " + value);
    }
}
