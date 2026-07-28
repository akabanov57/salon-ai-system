package salon.web.http.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

import io.avaje.http.client.JsonbBodyAdapter;
import io.avaje.inject.BeanScope;
import io.avaje.jex.Jex;
import io.avaje.jex.Jex.Server;
import io.avaje.http.client.HttpClient;
import io.avaje.jsonb.Jsonb;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import salon.api.model.Client;
import salon.api.service.AiAssistantService;
import salon.api.service.BookingService;
import salon.api.service.NotificationService;
import salon.web.http.web.WebhookController.IncomingMessageDto;

class WebhookControllerAvajeClientTest {

  // Shared static references pinned once for the entire lifecycle footprint
  private static BookingService bookingServiceMock;
  private static AiAssistantService aiAssistantServiceMock;
  private static NotificationService notificationServiceMock;
  private static BeanScope beanScope;
  private static Server server;
  private static HttpClient httpClient;

  @BeforeAll
  static void startComponent() {
    // Instantiate clear proxy bytecode engine stubs exactly once (Ultra performant)
    bookingServiceMock = Mockito.mock(BookingService.class);
    aiAssistantServiceMock = Mockito.mock(AiAssistantService.class);
    notificationServiceMock = Mockito.mock(NotificationService.class);

    // 2. Билдим scope модуля. Наша WebRouterConfiguration автоматически запустится внутри билдера!
    beanScope = BeanScope.builder()
        .beans(bookingServiceMock, aiAssistantServiceMock, notificationServiceMock)
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
    if (server != null) server.shutdown();
    if (beanScope != null) beanScope.close();
  }

  @Test
  void shouldReceivePostRequestAndRouteToServiceWithStatus204() {
    // Arrange
    IncomingMessageDto incomingMessage = new IncomingMessageDto(
        "12345678", "TELEGRAM", "Natalia", "Хочу записаться"
    );

    Client dummyClient = new Client(1L, "Natalia", null, null, "12345678", null, 0, LocalDateTime.now());

    Mockito.when(bookingServiceMock.identifyOrCreateTelegramClient("12345678", "Natalia"))
        .thenReturn(dummyClient);
    Mockito.when(aiAssistantServiceMock.processChat("12345678", "Хочу записаться"))
        .thenReturn("Пожалуйста, выберите мастера...");

    // Act
    // Execute native relative path invocation call using avaje-http-client without leading slash
    HttpResponse<String> response = httpClient.request()
        .path("api/v1/webhooks/message")
        .body(incomingMessage) // High-level object passing. The framework automatically encodes via Jsonb!
        .POST()
        .asString();

    // Assert
    assertEquals(204, response.statusCode(), "Void controller endpoints should cleanly return a 204 No Content response code.");
    verify(bookingServiceMock).identifyOrCreateTelegramClient("12345678", "Natalia");
    verify(aiAssistantServiceMock).processChat("12345678", "Хочу записаться");
    verify(notificationServiceMock).sendResponse("12345678", "Пожалуйста, выберите мастера...");
  }

}
