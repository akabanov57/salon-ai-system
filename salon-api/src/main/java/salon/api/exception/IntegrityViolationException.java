package salon.api.exception;

public class IntegrityViolationException extends SalonException {

  public IntegrityViolationException(String message) {
    super(message);
  }

  public IntegrityViolationException(String message, Throwable cause) {
    super(message, cause);
  }
}
