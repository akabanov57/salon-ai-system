package salon.web.http.internal.impl;

import io.avaje.config.Config;
import io.avaje.inject.PostConstruct;
import io.avaje.http.client.HttpClient; // CORRECT AVAJE IMPORT
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import salon.web.http.internal.services.TelegramBotClient;
import salon.web.http.internal.services.TelegramBotClient.SetWebhookDto;

/**
 * Внутренний инфраструктурный компонент автоматической регистрации вебхука.
 * <p>
 * Поскольку у этого класса нет внешних потребителей (клиентов) и он никогда не будет подменяться на
 * моки в тестах (он сам отключается флагом publicUrl.isBlank()), создание интерфейса для него — это
 * избыточное усложнение (нарушение принципа YAGNI).
 */
@Singleton
final class TelegramWebhookInitializer {

  private static final Logger log = LoggerFactory.getLogger(TelegramWebhookInitializer.class);

  private final TelegramBotClient telegramBotClient;
  private final String urlTokenPrefix;
  private final String publicUrl;
  private final String secretToken;

  TelegramWebhookInitializer(HttpClient baseHttpClient) {
    String rawBotToken = Config.get("telegram.bot.token", "");
    this.urlTokenPrefix = "bot" + rawBotToken;
    this.publicUrl = Config.get("server.public.url", "");
    this.secretToken = Config.get("telegram.webhook.secret-token", "");

    this.telegramBotClient = baseHttpClient.create(TelegramBotClient.class);
  }

  @PostConstruct
  public void registerWebhookOnStartup() {
    if (publicUrl.isBlank() || urlTokenPrefix.equals("bot")) {
      log.info("[Telegram Initializer] Регистрация пропущена: отсутствует публичный URL или токен бота (Локальный режим).");
      return;
    }

    final String fullWebhookEndpoint = publicUrl.trim() + "/api/v1/webhooks/message";
    log.info("[Telegram Initializer] Отправка запроса в Telegram для регистрации вебхука: {}", fullWebhookEndpoint);

    try {
      final SetWebhookDto registrationPayload = new SetWebhookDto(fullWebhookEndpoint, secretToken);
      telegramBotClient.setWebhook(urlTokenPrefix, registrationPayload);
      log.info("[Telegram Initializer] УСПЕХ: Вебхук успешно зарегистрирован на серверах Telegram.");
    } catch (Exception e) {
      log.error("[Telegram Initializer] КРИТИЧЕСКАЯ ОШИБКА: Не удалось связаться с Telegram Bot API: {}", e.getMessage(), e);
    }
  }
}
