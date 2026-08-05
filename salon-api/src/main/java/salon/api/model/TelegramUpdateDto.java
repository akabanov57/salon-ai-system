package salon.api.model;

import io.avaje.jsonb.Json;

/**
 * <h2>Сетевой DTO: Точное структурное зеркало вебхука Telegram Update</h2>
 * <pre><code>
 * {
 *   "update_id": 876543210,
 *   "message": {
 *     "message_id": 42,
 *     "from": {
 *       "id": 123456789,
 *       "is_bot": false,
 *       "first_name": "Иван",
 *       "username": "ivan_dev",
 *       "language_code": "ru"
 *     },
 *     "chat": {
 *       "id": 123456789,
 *       "first_name": "Иван",
 *       "username": "ivan_dev",
 *       "type": "private"
 *     },
 *     "date": 1718714000,
 *     "text": "Привет! Как дела?"
 *   }
 * }
 * </code></pre>
 * Размещен в общем доменном модуле API для обеспечения сквозного логирования
 * и верификации идемпотентности во всех слоях приложения.
 */
@Json
public record TelegramUpdateDto(
    @Json.Property("update_id")
    Long updateId,

    @Json.Property("message")
    MessageContent message

) {
  @Json
  public record MessageContent(
      @Json.Property("message_id")
      Long messageId,

      @Json.Property("from")
      FromUser from,

      @Json.Property("chat")
      ChatDetails chat,

      String text
  ) {}

  @Json
  public record FromUser(
      @Json.Property("first_name")
      String firstName
  ) {}

  @Json
  public record ChatDetails(
      Long id
  ) {}

}
