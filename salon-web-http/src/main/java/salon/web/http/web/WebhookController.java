package salon.web.http.web;

import io.avaje.http.api.Body;
import io.avaje.http.api.Controller;
import io.avaje.http.api.Path;
import io.avaje.http.api.Post;
import io.avaje.inject.External;
import io.avaje.jex.http.Context;
import io.avaje.jex.http.HttpStatus;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import salon.api.exception.AiEngineException;
import salon.api.exception.IntegrityViolationException;
import salon.api.exception.MessageIdempotencyException;
import salon.api.exception.StorageInfrastructureException;
import salon.api.model.PlatformType;
import salon.api.model.ProcessMessageCommand;
import salon.api.model.TelegramUpdateDto;
import salon.api.service.AiAssistantService;
import salon.api.service.BookingService;
import salon.api.service.MessageTraceService;
import salon.api.service.NotificationService;

/**
 * <h2>Центральный диспетчер автоматизации входящих коммуникаций салона</h2>
 * <p>
 * Контроллер отвечает за координацию полного жизненного цикла обработки текстовых
 * сообщений от клиентов. Он принимает сырые сетевые пакеты данных, переводит их
 * на язык доменных команд бизнеса и обеспечивает гарантированный ответ пользователю
 * даже в случае критического отказа внутренних подсистем (СУБД или нейросети ИИ).
 * </p>
 */
@Controller
@Path("/api/v1/webhooks")
public class WebhookController {

  private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

  private final BookingService bookingService;

  private final AiAssistantService aiAssistantService; // INJECT THE AI PORT INTERFACE

  private final MessageTraceService messageTraceService;

  private final NotificationService notificationService; // ДОБАВЛЯЕМ В КЛАСС

  @Inject
  public WebhookController(@External BookingService bookingService,
      @External AiAssistantService aiAssistantService,
      @External MessageTraceService messageTraceService,
      @External NotificationService notificationService) {
    this.bookingService = bookingService;
    this.aiAssistantService = aiAssistantService;
    this.messageTraceService = messageTraceService;
    this.notificationService = notificationService;
  }

  /**
   * <h3>Центральный оркестратор обработки входящего сообщения (Gateway Webhook)</h3>
   *
   * <p>Метод координирует полный сквозной (end-to-end) жизненный цикл обработки текстовых
   * запросов от клиентов, прибывающих из внешних платформ коммуникации (Telegram, Instagram).
   * Он обеспечивает надежную изоляцию сбоев подсистем и гарантирует отправку ответа
   * пользователю даже в случае полного отказа СУБД или нейросетевого ядра ИИ.</p>
   *
   * <h4>Бизнес-ценность метода:</h4>
   * <ul>
   *   <li><b>Исключение "мертвого молчания" (UX Protection):</b> Любой технический сбой
   *       автоматически переводится в вежливый, понятный человеку текст-заглушку. Пользователь
   *       всегда знает, что происходит, и не сталкивается с зависшим чатом.</li>
   *   <li><b>Защита от сетевого спама вебхуков (Idempotency / Retry Shield):</b> Метод перехватывает
   *       ошибки слоев внутри приложения и всегда возвращает статус <code>HTTP 200 OK</code>.
   *       Это сигнализирует облачным серверам мессенджеров (Telegram/Meta), что пакет успешно принят,
   *       и предотвращает бесконечные циклы повторных доставок (Retry Swamps) одного запроса.</li>
   *   <li><b>Достоверность архивных журналов:</b> Обеспечивает строгую запись отправленного на экран
   *       клиента сообщения в базу данных для последующего анализа качества работы ИИ-ассистента
   *       и разбора спорных ситуаций.</li>
   * </ul>
   *
   * <h4>Двухфазная архитектура обработки исключений (Fault-Tolerance Matrix):</h4>
   * <table border="1">
   *   <thead>
   *     <tr>
   *       <th>Тип Исключения</th>
   *       <th>Причина сбоя</th>
   *       <th>Внутренняя реакция системы</th>
   *       <th>Результат для клиента</th>
   *     </tr>
   *   </thead>
   *   <tbody>
   *     <tr>
   *       <td><b>{@link IntegrityViolationException}</b></td>
   *       <td>Конкурентная гонка потоков (Race Condition), когда пользователь быстро спамит кнопку отправки.</td>
   *       <td><b>Подавляется внутри контроллера.</b> Повторная вставка в БД блокируется, запрос напрямую перенаправляется в ИИ-ядро.</td>
   *       <td><i>Бесшовный.</i> Клиент получает легитимный ответ нейросети без задержек.</td>
   *     </tr>
   *     <tr>
   *       <td><b>{@link StorageInfrastructureException}</b></td>
   *       <td>СУБД Postgres лежит, исчерпан пул HikariCP или отсутствует сеть на уровне базы.</td>
   *       <td>Выполнение бизнес-логики прерывается, вызов ИИ-генерации блокируется ради экономии ресурсов. Формируется аварийный текст.</td>
   *       <td><i>"Извините, в нашей системе записи произошел технический сбой..."</i></td>
   *     </tr>
   *     <tr>
   *       <td><b>{@link AiEngineException}</b></td>
   *       <td>Таймаут Ollama, превышение контекстного окна токенов или сбой API OpenAI.</td>
   *       <td>Изолирует сбой ИИ-ядра. Так как Шаг А (БД) уже зафиксирован, входящий лог сохраняется, а клиенту отдается экстренный шаблон.</td>
   *       <td><i>"Наш онлайн-ассистент перегружен. Пожалуйста, свяжитесь с администратором по телефону..."</i></td>
   *     </tr>
   *     <tr>
   *       <td><b>{@link Exception} (Общий)</b></td>
   *       <td>Непредвиденная системная аномалия или NullPointerException внутри внутренних утилит.</td>
   *       <td>Логируется критическая ошибка, формируется стандартное системное сообщение об офлайне.</td>
   *       <td><i>"Извините, сервис временно недоступен..."</i></td>
   *     </tr>
   *   </tbody>
   * </table border="1">
   *
   * <h4>Жизненный цикл исполнения потока:</h4>
   * <ol>
   *   <li><b>Контур Изоляции Логики (Зона 1):</b> Начинается линейный "Счастливый путь" (Happy Path).
   *       Транспортный DTO преобразуется в валидную доменную команду.
   *       Вызывается транзакционный метод <code>bookingService.processMessage()</code>,
   *       который атомарно находит/создает клиента и фиксирует <code>INBOUND</code> лог на диске.
   *       Затем управление передается в нейросетевой чат ИИ. Любой сбой на этих этапах перехватывается
   *       соответствующим блоком <code>catch</code> и переводится в безопасную переменную <code>finalOutboundText</code>.</li>
   *   <li><b>Контур Гарантированной Сетевой Границы (Зона 2):</b> Находится в независимом изолированном
   *       блоке <code>try-catch</code> в самом конце метода. Исполняется <b>всегда</b>, независимо от исхода Зоны 1.
   *       Метод отправляет сформированный текст клиенту в мессенджер через <code>notificationService</code>.
   *       Затем, если из-за падения СУБД в первой фазе был пропущен входящий лог, контур выполняет
   *       экстренное восстановление журнала (Emergency Recovery), дописывая пропущенный <code>INBOUND</code>,
   *       и завершает цикл финальной фиксацией архивной строки <code>OUTBOUND</code>.</li>
   * </ol>
   *
   * @param payload Сетевой контракт входящих данных (JSON-модель), полученный от сетевого слоя Jex/Jetty.
   * @see ProcessMessageCommand
   * @see BookingService
   * @see AiAssistantService
   * @see MessageTraceService
   * @see NotificationService
   */
  @Post("/telegram")
  public void handleIncomingMessage(@Body TelegramUpdateDto payload, Context ctx) {
    final String rawPlatformId = String.valueOf(payload.message().chat().id());
    final String rawDisplayName = payload.message().from().firstName();
    final String rawText = payload.message().text();
    final PlatformType platformType = PlatformType.TELEGRAM;

    log.info("Network Boundary: Intercepted inbound genuine Telegram webhook for platform ID: {}", rawPlatformId);

    final String currentTraceId = MDC.get("traceId");

    final String rawInboundJson = ctx.body();

    String finalOutboundText;
    boolean inboundAlreadyArchived = false;

    // FIX: Construct the command object outside the try block so it is visible to all catch blocks!
    final ProcessMessageCommand domainCommand = new ProcessMessageCommand(
        currentTraceId, platformType, rawPlatformId, String.valueOf(payload.updateId()), rawDisplayName, rawText
    );

    try {

      // 1. Dispatch the domain command to handle client check/creation and inbound logging
      bookingService.processMessage(domainCommand);
      inboundAlreadyArchived = true;

      // 2. Generate the regular AI assistant response
      // FIX: Pass the clean domain command instead of raw strings!
      finalOutboundText = aiAssistantService.processChat(domainCommand);

    } catch (MessageIdempotencyException idempotencyEx) {
      log.warn("Network Boundary: Обнаружен сетевой дубликат [Update ID: {}]. Глушение запроса (200 OK).",
          domainCommand.messengerMessageId());
      ctx.status(HttpStatus.OK_200).text("");
      return;
    } catch (IntegrityViolationException integrityEx) {
      // FIX: Concurrent request race condition recovery!
      // Thread B hit a collision because Thread A just successfully saved this client profile.
      log.warn("Network Boundary: Concurrent payload collision trapped for Trace ID [{}]. " +
          "Client already created by a parallel thread. Recovering silently.", currentTraceId);

      // Mark as archived because Thread A already stored the INBOUND log row for this request cycle
      inboundAlreadyArchived = true;

      try {
        // FIX: Pass the clean domain command inside the race-condition recovery pass!
        finalOutboundText = aiAssistantService.processChat(domainCommand);
      } catch (Exception aiEx) {
        log.error(
            "Network Boundary: AI core failed during race-condition recovery pass [Trace: {}]",
            currentTraceId, aiEx);
        finalOutboundText = "Наш онлайн-ассистент сейчас перегружен. Чтобы записаться прямо "
            + "сейчас, пожалуйста, свяжитесь с администратором салона по телефону.";
      }

    } catch (StorageInfrastructureException dbDownEx) { // Удалять нельзя!!!
      // The actual database server is completely offline or compromised
      log.error("Network Boundary: Critical Storage Infrastructure Fault [Trace: {}].",
          currentTraceId, dbDownEx);
      finalOutboundText = "Извините, в нашей системе записи произошел технический сбой. "
          + "Пожалуйста, повторите попытку через пару минут.";

      ctx.status(HttpStatus.SERVICE_UNAVAILABLE_503).text("");
    } catch (AiEngineException aiEx) {
      // Database is perfectly fine, but the LLM/Ollama engine dropped or timed out
      log.error("Network Boundary: Generative AI Core Dropout [Trace: {}].", currentTraceId, aiEx);
      finalOutboundText = "Наш онлайн-ассистент сейчас перегружен. Чтобы записаться прямо сейчас,"
          + " пожалуйста, свяжитесь с администратором салона по телефону.";

    } catch (Exception unexpectedEx) {
      log.error("Network Boundary: Unexpected system anomaly detected [Trace: {}]", currentTraceId,
          unexpectedEx);
      finalOutboundText = "Извините, сервис временно недоступен. Пожалуйста, попробуйте отправить"
          + " сообщение позже.";
    }

    // =====================================================================
    // FINAL DISPATCH & AUDIT ROUTING (ALWAYS EXECUTES)
    // =====================================================================
    try {
      notificationService.sendResponse(rawPlatformId, finalOutboundText);

      if (!inboundAlreadyArchived) {
        messageTraceService.logTrace(
            currentTraceId, platformType, rawPlatformId,
            MessageTraceService.Direction.INBOUND, rawInboundJson, rawText // Сохраняем истинный сырой JSON из буфера Jex!
        );
      }

      messageTraceService.logTrace(
          currentTraceId, platformType, rawPlatformId,
          MessageTraceService.Direction.OUTBOUND, null, finalOutboundText
      );

      log.info(
          "Network Boundary: Conversational round-trip completed successfully for Trace ID: [{}].",
          currentTraceId);

    } catch (Exception deliveryEx) {
      log.error(
          "Network Boundary: Fatal edge delivery or logging block exception for Trace ID [{}]: {}",
          currentTraceId, deliveryEx.getMessage(), deliveryEx);
    }
    ctx.status(HttpStatus.OK_200).text("");
  }

}
