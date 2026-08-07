package salon.web.http.web;

import io.avaje.http.api.Controller;
import io.avaje.http.api.ExceptionHandler;
import io.avaje.http.api.Filter;
import io.avaje.inject.External;
import io.avaje.jex.http.Context;
import io.avaje.jex.http.HttpFilter.FilterChain;
import io.avaje.jex.http.HttpStatus;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import salon.api.exception.IntegrityViolationException;
import salon.api.exception.StorageInfrastructureException;
import salon.api.model.PlatformType;
import salon.api.model.TelegramUpdateDto;
import salon.api.service.IdempotencyService;
import salon.api.service.NotificationService;
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

  // Унифицированный эндпоинт для точного матчинга на границе сети
  private static final String TELEGRAM_WEBHOOK_PATH = "/api/v1/webhooks/telegram";

  private final TelegramVerificationService telegramService;
  private final IdempotencyService idempotencyService;
  private final NotificationService notificationService;

  WebFilters(
      @External TelegramVerificationService telegramService,
      @External IdempotencyService idempotencyService,
      @External NotificationService notificationService) {
    this.telegramService = telegramService;
    this.idempotencyService = idempotencyService;
    this.notificationService = notificationService;
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
    if ("POST".equalsIgnoreCase(ctx.method()) && TELEGRAM_WEBHOOK_PATH.equals(ctx.path())) {
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

  /**
   * ФИЛЬТР 3: Защитный замок идемпотентности (Сценарий 1)
   */
  @Filter
  void idempotencyGuard(FilterChain chain, Context ctx) {
    if ("POST".equalsIgnoreCase(ctx.method()) && TELEGRAM_WEBHOOK_PATH.equals(ctx.path())) {
      // 1. Быстро мапим честное зеркало Telegram API для извлечения координат
      TelegramUpdateDto payload = ctx.bodyAsClass(TelegramUpdateDto.class);
      String messengerMessageId = String.valueOf(payload.updateId());

      try {
        // 2. Атомарно пытаемся захватить составной PK в базе данных через интерфейс API
        idempotencyService.tryAcquireLock(PlatformType.TELEGRAM, messengerMessageId);

      } catch (IntegrityViolationException integrityEx) {
        // СИТУАЦИЯ А: Обнаружен легитимный сетевой дубликат (Сценарий 1)
        log.warn("Сетевой фильтр: Обнаружен повторный пакет Telegram [ID: {}]. Глушение запроса (200 OK).",
            messengerMessageId);

        throw integrityEx;

      }
      // StorageInfrastructureException автоматически вылетит наверх
      // в свой собственный @ExceptionHandler без нашего участия!
    }

    // Если пакет уникален и СУБД стабильна — передаем выполнение в WebhookController
    chain.proceed();
  }

  @ExceptionHandler(IntegrityViolationException.class)
  void idempotencyException(Context ctx) {
    ctx.status(HttpStatus.OK_200);
  }

  /**
   * <h3>Глобальный обработчик критических аварий СУБД на границе сети</h3>
   *
   * <p>Перехватывает падение базы данных, отправляет клиенту вежливый аварийный ответ
   * и возвращает статус 503 мессенджеру для отложенного авто-повтора.</p>
   */
  @ExceptionHandler(StorageInfrastructureException.class)
  void storageInfrastructureException(StorageInfrastructureException ex, Context ctx) {
    final String currentTraceId = MDC.get("traceId");
    log.error("Сетевой контур: Перехвачена критическая авария хранилища данных [Trace: {}]. Формируется экстренный UX.",
        currentTraceId, ex);

    try {
      // Быстро вытаскиваем координаты клиента из закэшированного Jex-контекста
      final TelegramUpdateDto payload = ctx.bodyAsClass(TelegramUpdateDto.class);
      final String rawPlatformId = String.valueOf(payload.message().chat().id());

      // Отправляем вежливое сообщение в обход упавшего бэкенда персистентности
      notificationService.sendResponse(rawPlatformId,
          "Извините, в нашей системе записи произошел технический сбой. Пожалуйста, повторите попытку через пару минут.");

    } catch (Exception deliveryEx) {
      log.error("Сетевой контур: Не удалось доставить экстренное сообщение пользователю [Trace: {}]",
          currentTraceId, deliveryEx);
    }

    // Возвращаем мессенджеру 503 Service Unavailable для принудительного Retry полиси
    ctx.status(HttpStatus.SERVICE_UNAVAILABLE_503)
        .text("Service Unavailable: Central integrity database engine is offline.");
  }
}
