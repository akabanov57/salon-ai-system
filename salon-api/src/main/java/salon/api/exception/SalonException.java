package salon.api.exception;

import java.util.Map;

public abstract class SalonException extends RuntimeException {

  // Immutable collection mapping exact contextual tracking markers
  private final Map<String, Object> errorContext;

  public SalonException(String message) {
    super(message);
    this.errorContext = Map.of();
  }

  public SalonException(String message, Throwable cause) {
    super(message, cause);
    this.errorContext = Map.of();
  }

  public SalonException(String message, Map<String, Object> context, Throwable cause) {
    super(message, cause);
    this.errorContext = context != null ? Map.copyOf(context) : Map.of();
  }

  public Map<String, Object> getErrorContext() {
    return errorContext;
  }
}
