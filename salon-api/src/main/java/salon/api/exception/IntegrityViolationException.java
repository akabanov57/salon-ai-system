package salon.api.exception;

import java.util.Map;

/**
 * <h2>Исключение нарушения целостности данных бизнес-логики</h2>
 * Выбрасывается при конфликтах уникальных ключей СУБД, например, при попытке
 * повторного создания уже существующего профиля клиента мессенджера.
 */
public class IntegrityViolationException extends SalonException {

  public IntegrityViolationException(String message) {
    super(message);
  }

  public IntegrityViolationException(String message, Throwable cause) {
    super(message, cause);
  }

  public IntegrityViolationException(String message, Map<String, Object> context, Throwable cause) {
    super(message, context, cause);
  }
}
