package dev.thedusk.minecraftserver.encounter;

public final class EncounterException extends RuntimeException {
    public enum Code {
        NOT_FOUND,
        INVALID_STATE,
        IDEMPOTENCY_CONFLICT,
        STORAGE_FAILURE
    }

    private final Code code;

    public EncounterException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public EncounterException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
