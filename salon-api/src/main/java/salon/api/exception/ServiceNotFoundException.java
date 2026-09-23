package salon.api.exception;

public final class ServiceNotFoundException extends RuntimeException {
  public ServiceNotFoundException(String message) { super(message); }
}
