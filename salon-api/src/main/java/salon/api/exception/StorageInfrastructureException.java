package salon.api.exception;

import java.util.Map;

/**
 * <h2>Исключение инфраструктуры слоя хранения данных</h2>
 * Сигнализирует о критических сбоях СУБД: отсутствии сетевого подключения к PostgreSQL,
 * исчерпании пула соединений HikariCP или аппаратных ошибках диска.
 */
public class StorageInfrastructureException extends SalonException {

  public StorageInfrastructureException(String message) {
    super(message);
  }

  public StorageInfrastructureException(String message, Throwable cause) {
    super(message, cause);
  }

  public StorageInfrastructureException(String message, Map<String, Object> context, Throwable cause) {
    super(message, context, cause);
  }
}
