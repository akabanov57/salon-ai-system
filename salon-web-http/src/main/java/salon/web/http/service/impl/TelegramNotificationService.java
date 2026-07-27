package salon.web.http.service.impl;

import io.avaje.config.Config;
import io.avaje.http.client.HttpClient; // CORRECT AVAJE IMPORT
import io.avaje.jsonb.Jsonb;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import salon.api.service.NotificationService;
import salon.web.http.internal.services.TelegramBotClient;
import salon.web.http.internal.services.TelegramBotClient.SendMessageDto;

/**
 * Outbound Infrastructure Adapter implementing the NotificationService business port. Housed in a
 * standardized port implementation layer.
 */
@Singleton
final class TelegramNotificationService implements NotificationService {

  private static final Logger log = LoggerFactory.getLogger(TelegramNotificationService.class);

  private final TelegramBotClient telegramBotClient;

  private final String urlTokenPrefix;

  /**
   * Инициализация зависимостей через final-поля в конструкторе — это самый безопасный, скоростной и
   * защищенный от утечек памяти паттерн в Java. @PostConstruct нужен только тогда, когда компоненту
   * для старта требуется логика, завязанная на циклы (например, запуск фонового потока, который
   * должен начать выполняться строго после того, как весь контекст DI-приложения уже полностью
   * собран).
   *
   * @param baseHttpClient HttpClient, который был создан в WebConfiguration
   * @see salon.web.http.configuration.WebConfiguration#baseHttpClient(Jsonb)
   */
  @Inject
  TelegramNotificationService(HttpClient baseHttpClient) {
    String rawBotToken = Config.get("telegram.bot.token", "");

    // Маппим префикс URL для Telegram Bot API
    this.urlTokenPrefix = "bot" + rawBotToken;

    // ИСПРАВЛЕНО: Прямой вызов .create() на экземпляре HttpClient для сборки декларативного клиента
    this.telegramBotClient = baseHttpClient.create(TelegramBotClient.class);
  }

  @Override
  public void sendResponse(String platformId, String message) {

    if (urlTokenPrefix.equals("bot")) {
      log.warn("Отмена отправки: параметр 'telegram.bot.token' не задан в конфигурации.");
      return;
    }

    log.info("Отправка исходящего сообщения через Telegram Bot API для Chat ID: {}", platformId);
    try {
      SendMessageDto payload = new SendMessageDto(platformId, message);
      telegramBotClient.sendMessage(urlTokenPrefix, payload);
      log.debug("Запрос на отправку сообщения успешно передан в сетевой стек.");
    } catch (Exception e) {
      log.error("Ошибка Telegram Bot API: Не удалось доставить ответ пользователю. Причина: {}",
          e.getMessage(), e);
    }
  }
}
