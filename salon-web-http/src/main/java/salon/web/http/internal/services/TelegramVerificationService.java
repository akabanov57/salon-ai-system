package salon.web.http.internal.services;

/**
 * Внутренний контракт для проверки подлинности входящих HTTP-запросов от Telegram Bot API.
 * Изолирован внутри пакета .internal.services.
 */
public interface TelegramVerificationService {

  /**
   * Проверяет, совпадает ли токен из заголовка запроса с ожидаемым секретом.
   *
   * @param headerToken Токен, извлеченный из HTTP-заголовка.
   * @return true, если токен валиден или проверка отключена.
   */
  boolean isValidTelegramRequest(String headerToken);
}
