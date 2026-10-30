package edu.delivery.contract;

/** Stable persistence error codes. */
public final class StoreException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public enum Code {
    NOT_FOUND,
    DUPLICATE,
    STALE_DATA,
    CONFLICT,
    INVALID_DATA,
    STORAGE_ERROR,
    TRANSACTION_REQUIRED
  }

  private final Code code;

  public StoreException(Code code, String message) {
    super(message);
    this.code = java.util.Objects.requireNonNull(code);
  }

  public StoreException(Code code, String message, Throwable cause) {
    super(message, cause);
    this.code = java.util.Objects.requireNonNull(code);
  }

  public Code code() {
    return code;
  }
}
