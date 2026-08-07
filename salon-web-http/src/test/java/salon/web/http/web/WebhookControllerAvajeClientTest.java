package salon.web.http.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.avaje.http.client.JsonbBodyAdapter;
import io.avaje.inject.BeanScope;
import io.avaje.jex.Jex;
import io.avaje.jex.Jex.Server;
import io.avaje.http.client.HttpClient;
import io.avaje.jsonb.Jsonb;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import salon.api.exception.IntegrityViolationException;
import salon.api.exception.StorageInfrastructureException;
import salon.api.model.PlatformType;
import salon.api.model.ProcessMessageCommand;
import salon.api.model.TelegramUpdateDto;
import salon.api.service.AiAssistantService;
import salon.api.service.BookingService;
import salon.api.service.IdempotencyService;
import salon.api.service.MessageTraceService;
import salon.api.service.NotificationService;

class WebhookControllerAvajeClientTest {

  // Shared static references pinned once for the entire lifecycle footprint
  private static final BookingService bookingServiceMock = Mockito.mock(BookingService.class);
  private static final AiAssistantService aiAssistantServiceMock = Mockito.mock(
      AiAssistantService.class);
  private static final NotificationService notificationServiceMock = Mockito.mock(
      NotificationService.class);
  private static final MessageTraceService messageTraceServiceMock = Mockito.mock(
      MessageTraceService.class);
  private static final IdempotencyService idempotencyServiceMock = Mockito.mock(IdempotencyService.class);

  private static BeanScope beanScope;
  private static Server server;
  private static HttpClient httpClient;

  @BeforeAll
  static void startComponent() {

    // 2. Билдим scope модуля. Наша WebRouterConfiguration автоматически запустится внутри билдера!
    beanScope = BeanScope.builder()
        .beans(bookingServiceMock, aiAssistantServiceMock, notificationServiceMock,
            messageTraceServiceMock, idempotencyServiceMock)
        .build();

    // 3. Вытаскиваем уже ИДЕАЛЬНО настроенный Jex (с Jsonb, фильтрами и роутами) прямо из DI
    Jex jex = beanScope.get(Jex.class);
    // Jex сконфигурирован. Смотри WebConfiguration.
    server = jex.start();

    // 4. Подключаем клиент к порту рантайма
    Jsonb jsonb = beanScope.get(Jsonb.class);
    httpClient = HttpClient.builder()
        .baseUrl("http://localhost:" + server.port())
        .bodyAdapter(new JsonbBodyAdapter(jsonb))
        .build();
  }

  @AfterEach
  void resetMockState() {
    Mockito.reset(bookingServiceMock);
    Mockito.reset(aiAssistantServiceMock);
    Mockito.reset(notificationServiceMock);
    Mockito.reset(messageTraceServiceMock);
    Mockito.reset(idempotencyServiceMock);
  }

  @AfterAll
  static void stopComponent() {
    if (server != null) {
      server.shutdown();
    }
    if (beanScope != null) {
      beanScope.close();
    }
  }

  @Test
  void shouldReceivePostRequestAndRouteToServiceWithStatus204() {

    // Arrange
    String mockAiReply = "Пожалуйста, выберите мастера...";
    String expectedPlatformId = "12345678";
    long mockUpdateId = 876543210L;

    // 1. НАСТРАИВАЕМ ПОВЕДЕНИЕ МОКОВ под новые типы доменных команд и фильтр
    // Наш новый фильтр идемпотентности должен пропустить уникальный пакет
    Mockito.when(idempotencyServiceMock.tryAcquireLock(eq(PlatformType.TELEGRAM), eq(String.valueOf(mockUpdateId))))
        .thenReturn(true);

    Mockito.doNothing().when(bookingServiceMock).processMessage(any(ProcessMessageCommand.class));
    Mockito.when(aiAssistantServiceMock.processChat(any(ProcessMessageCommand.class)))
        .thenReturn(mockAiReply);

    // СОБИРАЕМ ЧЕСТНЫЙ, ВЛОЖЕННЫЙ TELEGRAM-ОБЪЕКТ вместо старого плоского IncomingMessageDto
    TelegramUpdateDto genuineTelegramUpdate = new TelegramUpdateDto(
        mockUpdateId, // update_id
        new TelegramUpdateDto.MessageContent(
            42L, // message_id
            new TelegramUpdateDto.FromUser("Natalia"), // first_name
            new TelegramUpdateDto.ChatDetails(Long.parseLong(expectedPlatformId)), // chat.id (Будущий platformId)
            "Хочу записаться" // text
        )
    );

    // Act: Выполняем реальный сетевой вызов по унифицированному эндпоинту
    HttpResponse<String> response = httpClient.request()
        .path("api/v1/webhooks/telegram") // FIX: Приведено к единому стандарту 'webhook' (в единственном числе)
        .body(genuineTelegramUpdate)    // Автоматическая маршализация JSON через встроенный Jsonb
        .POST()
        .asString();

    // Assert
    // FIX: Возвращаем 204 No Content для успешного void-контроллера, как вы и указали!
    assertEquals(204, response.statusCode(),
        "Void controller endpoints should cleanly return a 204 No Content response code.");

    // Верифицируем сквозное прохождение конвейера сетевой защиты и доменных служб
    verify(idempotencyServiceMock, times(1)).tryAcquireLock(eq(PlatformType.TELEGRAM), eq(String.valueOf(mockUpdateId)));
    verify(bookingServiceMock, times(1)).processMessage(any(ProcessMessageCommand.class));
    verify(aiAssistantServiceMock, times(1)).processChat(any(ProcessMessageCommand.class));

    // Верифицируем отправку сообщения клиенту в Telegram
    verify(notificationServiceMock, times(1)).sendResponse(expectedPlatformId, mockAiReply);

    // Верифицируем фиксацию исходящего сообщения ИИ в архивной таблице логов
    verify(messageTraceServiceMock, times(1)).logTrace(
        any(), // Любой сгенерированный Trace ID из MDC фильтра
        eq(PlatformType.TELEGRAM),
        eq(expectedPlatformId),
        eq(MessageTraceService.Direction.OUTBOUND),
        isNull(),
        eq(mockAiReply)
    );
  }

  @Test
  void shouldReturnStatus200WhenDuplicateTelegramWebhookIsDetected() {

    // Arrange
    long duplicateUpdateId = 876543210L;
    String expectedPlatformId = "12345678";

    // 1. Имитируем поведение для повторного пакета:
    // Использовать этот Arrange, если метод возвращает void/выбрасывает исключение при дубликатах:
    Mockito.doThrow(new IntegrityViolationException("Duplicate key slot"))
        .when(idempotencyServiceMock).tryAcquireLock(eq(PlatformType.TELEGRAM), eq(String.valueOf(duplicateUpdateId)));

    // СОБИРАЕМ ТОЧНЫЙ ДУБЛИКАТ ТАЛОНА ЗАПРОСА
    TelegramUpdateDto duplicateTelegramUpdate = new TelegramUpdateDto(
        duplicateUpdateId, // Тот же самый update_id, который якобы уже обрабатывался
        new TelegramUpdateDto.MessageContent(
            42L,
            new TelegramUpdateDto.FromUser("Natalia"),
            new TelegramUpdateDto.ChatDetails(Long.parseLong(expectedPlatformId)),
            "Хочу записаться"
        )
    );

    // Act: Выполняем реальный сетевой вызов (без ведущего слэша, как требует ваш avaje httpClient!)
    HttpResponse<String> response = httpClient.request()
        .path("api/v1/webhooks/telegram")
        .body(duplicateTelegramUpdate)
        .POST()
        .asString();

    // Assert
    // Железное правило: Фильтр обязан погасить повтор быстрым статусом 200 OK
    assertEquals(200, response.statusCode(),
        "Duplicate webhooks must be short-circuited with a clean 200 OK response code.");

    // ИЗОЛЯЦИЯ: Гарантируем, что фильтр сработал как замок, и домен/ИИ вообще не вызывались!
    verify(idempotencyServiceMock, times(1)).tryAcquireLock(eq(PlatformType.TELEGRAM), eq(String.valueOf(duplicateUpdateId)));
    verify(bookingServiceMock, never()).processMessage(any(ProcessMessageCommand.class));
    verify(aiAssistantServiceMock, never()).processChat(any(ProcessMessageCommand.class));
    verify(notificationServiceMock, never()).sendResponse(anyString(), anyString());
  }

  /**
   * <h3>Тест: Успешная сквозная обработка честного вебхука Telegram</h3>
   */
  @Test
  void shouldSuccessfullyProcessInboundGenuineTelegramWebhook() {
    // Arrange
    final String mockAiReply = "Пожалуйста, выберите мастера...";
    final String expectedPlatformId = "12345678";
    final String mockUpdateId = "876543210";

    // ШАГ 1: Настраиваем поведение нового сетевого предохранителя идемпотентности
    // Фильтр обязан пропустить этот оригинальный пакет как первый и уникальный
    Mockito.when(idempotencyServiceMock.tryAcquireLock(eq(PlatformType.TELEGRAM), eq(mockUpdateId)))
        .thenReturn(true);

    Mockito.doNothing().when(bookingServiceMock).processMessage(any(ProcessMessageCommand.class));
    Mockito.when(aiAssistantServiceMock.processChat(any(ProcessMessageCommand.class)))
        .thenReturn(mockAiReply);

    // Симулируем ОРИГИНАЛЬНЫЙ, глубоко вложенный JSON из официальной документации Telegram Bot API
    final String genuineTelegramJsonPayload = """
        {
          "update_id": 876543210,
          "message": {
            "message_id": 42,
            "from": {
              "id": 12345678,
              "is_bot": false,
              "first_name": "Natalia",
              "username": "natalia_hair_design",
              "language_code": "ru"
            },
            "chat": {
              "id": 12345678,
              "first_name": "Natalia",
              "username": "natalia_hair_design",
              "type": "private"
            },
            "date": 1785951840,
            "text": "Хочу записаться"
          }
        }
        """;

    // Act: Выполняем реальный сетевой вызов. Путь приведен к константе webhooks во множественном числе
    HttpResponse<String> response = httpClient.request()
        .path("api/v1/webhooks/telegram") // FIX: Приведено к верному эндпоинту 'webhooks'
        .body(genuineTelegramJsonPayload)
        .POST()
        .asString();

    // Assert
    // Во фреймворке Avaje HTTP методы контроллеров с типом возвращаемого значения void обязаны возвращать 204
    assertEquals(204, response.statusCode(),
        "Void controller endpoints should cleanly return a 204 No Content response code.");

    // Верифицируем, что сетевой фильтр, бизнес-слой и ИИ получили корректные вызовы
    verify(idempotencyServiceMock, times(1)).tryAcquireLock(eq(PlatformType.TELEGRAM), eq(mockUpdateId));
    verify(bookingServiceMock, times(1)).processMessage(any(ProcessMessageCommand.class));
    verify(aiAssistantServiceMock, times(1)).processChat(any(ProcessMessageCommand.class));

    // Верифицируем отправку сообщения клиенту в Telegram
    verify(notificationServiceMock, times(1)).sendResponse(expectedPlatformId, mockAiReply);

    // Верифицируем фиксацию исходящего сообщения в архивной таблице логов
    verify(messageTraceServiceMock, times(1)).logTrace(
        any(), // Любой сгенерированный Trace ID из MDC
        eq(PlatformType.TELEGRAM),
        eq(expectedPlatformId),
        eq(MessageTraceService.Direction.OUTBOUND),
        isNull(),
        eq(mockAiReply)
    );
  }

  /**
   * <h3>Сценарий 1 (Ситуация Б): Падение инфраструктуры СУБД на границе сети</h3>
   *
   * <p><b>Бизнес-цель:</b> Гарантировать сохранение безупречного клиентского опыта (UX) и
   * отказоустойчивость конвейера при полной недоступности центрального хранилища данных.
   * Клиент не должен столкнуться с "глухим молчанием" чата.</p>
   *
   * <p><b>Ход выполнения сценария:</b>
   * <ol>
   *   <li>База данных полностью офлайн — имитируется через выброс {@link StorageInfrastructureException}
   *       на этапе захвата уникального замка дедупликации.</li>
   *   <li>Сетевой щит перехватывает аварийную доменную ошибку через декларативный метод
   *       {@code storageInfrastructureException}.</li>
   *   <li>Система защищает нижележащие слои, изолируя доменный контроллер и ИИ-движок от упавшего бэкенда.</li>
   *   <li>Клиент мгновенно получает в чат мессенджера вежливое экстренное сообщение о техническом сбое.</li>
   *   <li>Система возвращает серверам мессенджера статус {@code 503 Service Unavailable} для запуска
   *       политики отложенных автоматических повторов пакета (Retry Policy).</li>
   * </ol>
   * </p>
   */
  @Test
  void shouldReturnStatus503AndSendEmergencyMessageWhenDatabaseIsOffline() {

    // Arrange
    long mockUpdateId = 876543210L;
    String expectedPlatformId = "12345678";
    String emergencyMessage = "Извините, в нашей системе записи произошел технический сбой. Пожалуйста, повторите попытку через пару минут.";

    // Имитируем падение СУБД: замок идемпотентности выбрасывает инфраструктурное исключение
    Mockito.doThrow(new StorageInfrastructureException("Database connection timeout or crash"))
        .when(idempotencyServiceMock)
        .tryAcquireLock(eq(PlatformType.TELEGRAM), eq(String.valueOf(mockUpdateId)));

    // СОБИРАЕМ ВЛИДНЫЙ СЕТЕВОЙ ПАКЕТ TELEGRAM
    TelegramUpdateDto genuineTelegramUpdate = new TelegramUpdateDto(
        mockUpdateId,
        new TelegramUpdateDto.MessageContent(
            42L,
            new TelegramUpdateDto.FromUser("Natalia"),
            new TelegramUpdateDto.ChatDetails(Long.parseLong(expectedPlatformId)),
            "Хочу записаться"
        )
    );

    // Act: Выполняем реальный сетевой вызов к нашему конвейеру фильтров Jex
    HttpResponse<String> response = httpClient.request()
        .path("api/v1/webhooks/telegram")
        .body(genuineTelegramUpdate)
        .POST()
        .asString();

    // Assert
    // 1. Мессенджер обязан получить статус 503 Service Unavailable для авто-повтора пакета
    assertEquals(503, response.statusCode(),
        "The system must return a 503 Service Unavailable status when the persistence layer drops.");

    // 2. Доменный контроллер и ИИ гарантированно изолированы от упавшей БД
    verify(bookingServiceMock, never()).processMessage(any(ProcessMessageCommand.class));
    verify(aiAssistantServiceMock, never()).processChat(any(ProcessMessageCommand.class));

    // 3. ЖЕЛЕЗНЫЙ UX: Верифицируем, что клиенту улетело вежливое экстренное сообщение в чат мессенджера
    verify(notificationServiceMock, times(1)).sendResponse(expectedPlatformId, emergencyMessage);
  }
}
