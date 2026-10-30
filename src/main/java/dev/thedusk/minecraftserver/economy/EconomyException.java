package dev.thedusk.minecraftserver.economy;

public final class EconomyException extends RuntimeException {
    public enum Code {
        INSUFFICIENT_FUNDS,
        IDEMPOTENCY_CONFLICT,
        INVALID_MUTATION,
        STORAGE_FAILURE
    }

    private final Code code;

    public EconomyException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public EconomyException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
