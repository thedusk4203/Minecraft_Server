package dev.thedusk.minecraftserver.reward;

public final class EncounterRewardException extends RuntimeException {
    public enum Code {
        INVALID_STATE,
        SEMANTIC_CONFLICT,
        STORAGE_FAILURE
    }

    private final Code code;

    public EncounterRewardException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public EncounterRewardException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
