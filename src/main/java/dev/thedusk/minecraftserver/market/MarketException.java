package dev.thedusk.minecraftserver.market;

public final class MarketException extends RuntimeException {
    public enum Code {
        NOT_FOUND,
        NOT_OWNER,
        INVALID_STATE,
        IDEMPOTENCY_CONFLICT,
        STORAGE_FAILURE
    }

    private final Code code;

    public MarketException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public MarketException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
