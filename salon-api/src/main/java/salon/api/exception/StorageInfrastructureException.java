package salon.api.exception;

public class StorageInfrastructureException extends SalonException {

  public StorageInfrastructureException(String message) {
    super(message);
  }

  public StorageInfrastructureException(String message, Throwable cause) {
    super(message, cause);
  }
}
