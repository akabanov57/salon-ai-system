package salon.api.exception;

public abstract class SalonException extends RuntimeException {

  public SalonException(String message) {
    super(message);
  }

  public SalonException(String message, Throwable cause) {
    super(message, cause);
  }
}
