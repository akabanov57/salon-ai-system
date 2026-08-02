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
import salon.api.model.IncomingMessageDto;
import salon.api.model.PlatformType;
import salon.api.model.ProcessMessageCommand;
import salon.api.service.AiAssistantService;
import salon.api.service.BookingService;
import salon.api.service.MessageTraceService;
import salon.api.service.NotificationService;

class WebhookControllerAvajeClientTest {

  // Shared static references pinned once for the entire lifecycle footprint
  private static final BookingService bookingServiceMock = Mockito.mock(BookingService.class);
  private static final AiAssistantService aiAssistantServiceMock = Mockito.mock(AiAssistantService.class);
  private static final NotificationService notificationServiceMock = Mockito.mock(NotificationService.class);
  private static final MessageTraceService messageTraceServiceMock = Mockito.mock(MessageTraceService.class);

  private static BeanScope beanScope;
  private static Server server;
  private static HttpClient httpClient;

  @BeforeAll
  static void startComponent() {

    // 2. Билдим scope модуля. Наша WebRouterConfiguration автоматически запустится внутри билдера!
    beanScope = BeanScope.builder()
        .beans(bookingServiceMock, aiAssistantServiceMock, notificationServiceMock, messageTraceServiceMock)
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
    Mockito.when(aiAssistantServiceMock.processChat(any(ProcessMessageCommand.class))).thenReturn(mockAiReply);

    IncomingMessageDto incomingMessage = new IncomingMessageDto(
        "12345678", "TELEGRAM", "Natalia", "Хочу записаться"
    );

    // Act: Выполняем реальный сетевой вызов. Путь без '/' в начале, так как baseUrl уже содержит эндпоинт
    HttpResponse<String> response = httpClient.request()
        .path("api/v1/webhooks/message")
        .body(incomingMessage) // Автоматическая маршализация JSON через встроенный Jsonb Body Adapter
        .POST()
        .asString();

    // Assert
    // Во фреймворке Avaje HTTP методы контроллеров с типом возвращаемого значения void обязаны возвращать 204
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
