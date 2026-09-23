package salon.api.exception;

/**
 * <h2>Исключение нарушения идемпотентности входящих сообщений</h2>
 * Выбрасывается при обнаружении повторного сетевого пакета (дубликата),
 * зафиксированного по уникальному замку в таблице INBOUND_EVENTS.
 */
public class MessageIdempotencyException extends SalonException {

  public MessageIdempotencyException(String message) {
    super(message);
  }

  public MessageIdempotencyException(String message, Throwable cause) {
    super(message, cause);
  }
}
