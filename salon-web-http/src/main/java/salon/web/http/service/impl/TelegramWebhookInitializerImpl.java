package salon.web.http.service.impl;

import io.avaje.config.Config;
import io.avaje.http.client.HttpClient; // CORRECT AVAJE IMPORT
import io.avaje.inject.Prototype;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import salon.web.http.internal.services.TelegramBotClient;
import salon.web.http.internal.services.TelegramBotClient.SetWebhookDto;
import salon.api.service.TelegramWebhookInitializer;

/**
 * Внутренний инфраструктурный компонент автоматической регистрации вебхука.
 * <p>
 * Поскольку у этого класса нет внешних потребителей (клиентов) и он никогда не будет подменяться на
 * моки в тестах (он сам отключается флагом publicUrl.isBlank()), создание интерфейса для него — это
 * избыточное усложнение (нарушение принципа YAGNI).
 */
@Prototype
final class TelegramWebhookInitializerImpl implements TelegramWebhookInitializer {

  private static final Logger log = LoggerFactory.getLogger(TelegramWebhookInitializerImpl.class);

  private final TelegramBotClient telegramBotClient;
  private final String urlTokenPrefix;
  private final String publicUrl;
  private final String secretToken;
  private final boolean clearOnShutdown;

  TelegramWebhookInitializerImpl(HttpClient baseHttpClient) {
    this.telegramBotClient = baseHttpClient.create(TelegramBotClient.class);

    final String rawBotToken = Config.get("telegram.bot.token", "");
    this.urlTokenPrefix = "bot" + rawBotToken;
    this.publicUrl = Config.get("salon.telegram.webhook.url", "");
    this.secretToken = Config.get("telegram.webhook.secret-token", "");
    this.clearOnShutdown = Config.getBool("salon.telegram.webhook.clear-on-shutdown", false);
  }

  @Override
  public void registerWebhook() {
    // Если нет публичного URL салона или telegram.bot.token, то и регистрировать нечего.
    if (publicUrl.isBlank() || urlTokenPrefix.equals("bot")) {
      log.info("[Telegram Initializer] Регистрация пропущена: отсутствует публичный URL или токен бота (Локальный режим).");
      return;
    }

    final String fullWebhookEndpoint = publicUrl.trim() + "/api/v1/webhooks/telegram";
    log.info("[Telegram Initializer] Отправка запроса в Telegram для регистрации вебхука: {}", fullWebhookEndpoint);

    try {
      final SetWebhookDto registrationPayload = new SetWebhookDto(fullWebhookEndpoint, secretToken);
      telegramBotClient.setWebhook(urlTokenPrefix, registrationPayload);
      log.info("[Telegram Initializer] УСПЕХ: Вебхук успешно зарегистрирован на серверах Telegram.");

      if (clearOnShutdown) {
        registerWebhookCleanupShutdownHook();
      }
    } catch (Exception e) {
      log.error("[Telegram Initializer] КРИТИЧЕСКАЯ ОШИБКА: Не удалось связаться с Telegram Bot API: {}", e.getMessage(), e);
    }
  }

  /**
   * Регистрирует поток завершения в рантайме JVM для автоматического удаления вебхука.
   */
  private void registerWebhookCleanupShutdownHook() {
    log.info("[Telegram Initializer] Период опытной эксплуатации активен. Регистрация Shutdown Hook для очистки вебхука...");

    // КРИТИЧЕСКИ ВАЖНО ДЛЯ СБОРКИ МУСОРА (GC ЛИКВИДАЦИЯ УТЕЧКИ):
    // Извлекаем ссылки во внутренние локальные переменные. Если использовать поля инстанса напрямую внутри лямбды,
    // она неявно захватит жесткую ссылку на 'this'. Так как Shutdown Hook регистрируется в GC Root уровня JVM,
    // наш @Prototype инстанс утек бы в память навсегда. Локальные переменные полностью предотвращают эту утечку!
    final TelegramBotClient client = this.telegramBotClient;
    final String tokenPrefix = this.urlTokenPrefix;

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      log.info("[Telegram Initializer] JVM Shutdown пойман. Удаление вебхука через передачу пустого URL-адреса...");
      try {
        // Обходим отсутствие явного deleteWebhook: передача пустой строки в setWebhook
        // согласно официальной спецификации Telegram полностью сбрасывает регистрацию вебхука.
        client.setWebhook(tokenPrefix, new SetWebhookDto("", ""));
        log.info("[Telegram Initializer] УСПЕХ: Вебхук успешно сброшен. Сервера Telegram не будут копить очередь во время простоя.");
      } catch (Exception e) {
        log.error("[Telegram Initializer] КРИТИЧЕСКИЙ СБОЙ SHUTDOWN: Не удалось деактивировать вебхук в Telegram: {}", e.getMessage());
      }
    }));
  }
}
