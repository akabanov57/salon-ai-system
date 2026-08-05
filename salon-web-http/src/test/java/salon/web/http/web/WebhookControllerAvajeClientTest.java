package salon.web.http.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import salon.api.model.PlatformType;
import salon.api.model.ProcessMessageCommand;
import salon.api.model.TelegramUpdateDto;
import salon.api.service.AiAssistantService;
import salon.api.service.BookingService;
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

  private static BeanScope beanScope;
  private static Server server;
  private static HttpClient httpClient;

  @BeforeAll
  static void startComponent() {

    // 2. Билдим scope модуля. Наша WebRouterConfiguration автоматически запустится внутри билдера!
    beanScope = BeanScope.builder()
        .beans(bookingServiceMock, aiAssistantServiceMock, notificationServiceMock,
            messageTraceServiceMock)
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

    // Настраиваем поведение моков под новые типы доменных команд
    Mockito.doNothing().when(bookingServiceMock).processMessage(any(ProcessMessageCommand.class));
    Mockito.when(aiAssistantServiceMock.processChat(any(ProcessMessageCommand.class)))
        .thenReturn(mockAiReply);

    // СОБИРАЕМ ЧЕСТНЫЙ, ВЛОЖЕННЫЙ TELEGRAM-ОБЪЕКТ вместо старого плоского IncomingMessageDto
    TelegramUpdateDto genuineTelegramUpdate = new TelegramUpdateDto(
        876543210L, // update_id
        new TelegramUpdateDto.MessageContent(
            42L, // message_id
            new TelegramUpdateDto.FromUser("Natalia"), // first_name
            new TelegramUpdateDto.ChatDetails(12345678L), // chat.id (Будущий platformId)
            "Хочу записаться" // text
        )
    );

    // Act: Выполняем реальный сетевой вызов. Путь без '/' в начале, так как baseUrl уже содержит
    // эндпоинт
    HttpResponse<String> response = httpClient.request()
        .path("api/v1/webhooks/telegram")
        .body(
            genuineTelegramUpdate) // Автоматическая маршализация JSON через встроенный Jsonb
        // Body Adapter
        .POST()
        .asString();

    // Assert
    // Во фреймворке Avaje HTTP методы контроллеров с типом возвращаемого значения void обязаны
    // возвращать 204
    assertEquals(204, response.statusCode(),
        "Void controller endpoints should cleanly return a 204 No Content response code.");

    // Верифицируем, что бизнес-слой и ИИ получили корректные вызовы
    verify(bookingServiceMock, times(1)).processMessage(any(ProcessMessageCommand.class));
    verify(aiAssistantServiceMock, times(1)).processChat(any(ProcessMessageCommand.class));

    // Верифицируем отправку сообщения клиенту в Telegram
    verify(notificationServiceMock, times(1)).sendResponse("12345678", mockAiReply);

    // Верифицируем фиксацию исходящего сообщения в архивной таблице логов
    verify(messageTraceServiceMock, times(1)).logTrace(
        any(), // Любой сгенерированный Trace ID из MDC
        eq(PlatformType.TELEGRAM),
        eq("12345678"),
        eq(MessageTraceService.Direction.OUTBOUND),
        isNull(),
        eq(mockAiReply)
    );
  }

  /**
   * <h3>Тест: Успешная сквозная обработка честного вебхука Telegram</h3>
   */
  @Test
  void shouldSuccessfullyProcessInboundGenuineTelegramWebhook() {
    // Настраиваем поведение моков под новые типы доменных команд
    final String mockAiReply = "Пожалуйста, выберите мастера...";
    Mockito.doNothing().when(bookingServiceMock).processMessage(any(ProcessMessageCommand.class));
    Mockito.when(aiAssistantServiceMock.processChat(any(ProcessMessageCommand.class)))
        .thenReturn(mockAiReply);
    // Arrange
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

    // Act: Выполняем реальный сетевой вызов. Путь без '/' в начале, так как baseUrl уже содержит
    // эндпоинт
    HttpResponse<String> response = httpClient.request()
        .path("api/v1/webhooks/telegram")
        .body(
            genuineTelegramJsonPayload) // Автоматическая маршализация JSON через встроенный
        // Jsonb Body Adapter
        .POST()
        .asString();

    // Assert
    // Во фреймворке Avaje HTTP методы контроллеров с типом возвращаемого значения void обязаны
    // возвращать 204
    assertEquals(204, response.statusCode(),
        "Void controller endpoints should cleanly return a 204 No Content response code.");

    // Верифицируем, что бизнес-слой и ИИ получили корректные вызовы
    verify(bookingServiceMock, times(1)).processMessage(any(ProcessMessageCommand.class));
    verify(aiAssistantServiceMock, times(1)).processChat(any(ProcessMessageCommand.class));

    // Верифицируем отправку сообщения клиенту в Telegram
    verify(notificationServiceMock, times(1)).sendResponse("12345678", mockAiReply);

    // Верифицируем фиксацию исходящего сообщения в архивной таблице логов
    verify(messageTraceServiceMock, times(1)).logTrace(
        any(), // Любой сгенерированный Trace ID из MDC
        eq(PlatformType.TELEGRAM),
        eq("12345678"),
        eq(MessageTraceService.Direction.OUTBOUND),
        isNull(),
        eq(mockAiReply)
    );
  }
}
