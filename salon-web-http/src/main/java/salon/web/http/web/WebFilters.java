package salon.web.http.web;

import io.avaje.http.api.Controller;
import io.avaje.http.api.Filter;
import io.avaje.inject.External;
import io.avaje.jex.http.Context;
import io.avaje.jex.http.HttpFilter.FilterChain;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import salon.web.http.internal.services.TelegramVerificationService;

/**
 * <h2>Центральный узел сетевых фильтров веб-слоя салона красоты</h2>
 * <p>
 * Класс собирает всю сквозную (cross-cutting) логику обработки входящих HTTP-запросов.
 * Благодаря аннотации {@code @Filter}, генератор Avaje автоматически превращает методы
 * этого класса в цепочку перехватчиков (Interceptor Chain) для сервера Jex.
 * </p>
 * <p><b>Порядок выполнения фильтров:</b> Определяется сверху вниз по коду класса.</p>
 */
@Controller
final class WebFilters {
  private static final Logger log = LoggerFactory.getLogger(WebFilters.class);

  private final TelegramVerificationService telegramService;

  WebFilters(@External TelegramVerificationService telegramService) {
    this.telegramService = telegramService;
  }

  /**
   * <h3>ФИЛЬТР 1: Генерация сквозного Trace ID (Идентификатора операции)</h3>
   *
   * <p><b>Бизнес-цель:</b> Связать все логи в рамках одного обращения клиента в единую цепочку.
   * Если у клиента возникнет ошибка, по этому ID в логах можно мгновенно восстановить
   * хронологию событий от получения вебхука до ответа ИИ.</p>
   *
   * <p><b>Техническая суть:</b> Проверяет заголовок {@code X-Trace-ID} (он может прийти от балансировщика).
   * Если его нет, генерирует уникальный случайный маркер и помещает его в {@link MDC} (Mapped Diagnostic Context).
   * Блок {@code finally} гарантирует очистку контекста потока после завершения запроса,
   * предотвращая утечку данных в пуле потоков сервера.</p>
   */
  @Filter
  void traceIdInterceptor(FilterChain chain, Context ctx) {
    String traceId = ctx.header("X-Trace-ID");
    if (traceId == null || traceId.isBlank()) {
      traceId = "TX-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
    MDC.put("traceId", traceId);

    try {
      // Передаем запрос следующему фильтру (telegramSecurityGuard)
      chain.proceed();
    } finally {
      // Очищаем поток после того, как ВСЕ последующие фильтры и контроллеры отработали
      MDC.clear();
    }
  }

  /**
   * <h3>ФИЛЬТР 2: Сетевой щит безопасности Telegram (Блокировка спама и подделок)</h3>
   *
   * <p><b>Бизнес-цель:</b> Защитить сервер салона от злоумышленников. Наш адрес вебхука открыт всему интернету.
   * Этот фильтр гарантирует, что запросы приходят исключительно от реальных серверов Telegram,
   * предотвращая ложные записи и хакерские атаки на ИИ-движок.</p>
   *
   * <p><b>Техническая суть:</b> Перехватывает только POST-запросы на эндпоинт вебхука.
   * Проверяет секретный токен в заголовке {@code X-Telegram-Bot-Api-Secret-Token}, который мы сами
   * задали при регистрации бота. Если токен не совпадает, метод возвращает {@code 401 Unauthorized}
   * и делает {@code return}, полностью блокируя дальнейшее выполнение запроса (контроллер не вызовется).</p>
   */
  @Filter
  void telegramSecurityGuard(FilterChain chain, Context ctx) {
    if ("POST".equalsIgnoreCase(ctx.method()) && "/api/v1/webhooks/message".equals(ctx.path())) {
      String telegramHeaderToken = ctx.header("X-Telegram-Bot-Api-Secret-Token");

      boolean isAuthorized = telegramService.isValidTelegramRequest(telegramHeaderToken);
      if (!isAuthorized) {
        log.warn("Блокировка на границе сети: Неверный секретный токен вебхука Telegram. Доступ отклонен.");
        ctx.status(401).text("Unauthorized: Invalid webhook secret token source.");
        return; // МГНОВЕННЫЙ ОБРЫВ ЦЕПОЧКИ: Безопасность превыше всего
      }
    }
    // Если запрос валидный или ведет на другой эндпоинт — идем дальше к фильтру аудита
    chain.proceed();
  }

}
