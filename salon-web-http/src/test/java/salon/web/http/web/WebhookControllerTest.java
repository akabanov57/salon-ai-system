package salon.web.http.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.net.http.HttpClient;
import io.avaje.inject.BeanScope;
import io.avaje.jex.Jex;
import io.avaje.jex.Jex.Server;
import io.avaje.jsonb.Jsonb;
import java.net.URI;
import java.net.http.HttpRequest;
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

class WebhookControllerTest {

  // Shared static references pinned once for the entire lifecycle footprint
  // 1. Создаем мок внешних зависимостей
  private static final BookingService bookingServiceMock = Mockito.mock(BookingService.class);
  private static final AiAssistantService aiAssistantServiceMock = Mockito.mock(AiAssistantService.class);
  private static final NotificationService notificationServiceMock = Mockito.mock(NotificationService.class);

  private static BeanScope beanScope;
  private static Server server;
  private static java.net.http.HttpClient httpClient;
  private static Jsonb jsonb;

  @BeforeAll
  static void startComponent() {
    // 2. Билдим scope модуля. Наша WebRouterConfiguration автоматически запустится внутри билдера!
    beanScope = BeanScope.builder()
        .beans(bookingServiceMock, aiAssistantServiceMock, notificationServiceMock)
        .build();

    jsonb = beanScope.get(Jsonb.class);

    // 3. Вытаскиваем уже ИДЕАЛЬНО настроенный Jex (с Jsonb, фильтрами и роутами) прямо из DI
    Jex jex = beanScope.get(Jex.class);

    // Запускаем сервер на случайном порту для теста
    server = jex.start();

    // 4. Подключаем клиент к порту рантайма
    httpClient = HttpClient.newBuilder()
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
  void shouldReceivePostRequestAndRouteToServiceWithStatus200()
      throws IOException, InterruptedException {
    IncomingMessageDto incomingMessage = new IncomingMessageDto("12345678", "TELEGRAM", "Natalia", "Хочу записаться");
    String jsonPayload = jsonb.toJson(incomingMessage);

    Client dummyClient = new Client(1L, "Natalia", null, null, "12345678", null, 0, LocalDateTime.now());
    Mockito.when(bookingServiceMock.identifyOrCreateTelegramClient("12345678", "Natalia"))
        .thenReturn(dummyClient);
    Mockito.when(aiAssistantServiceMock.processChat("12345678", "Хочу записаться"))
        .thenReturn("Пожалуйста, выберите мастера...");

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + server.port() + "/api/v1/webhooks/message"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
        .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    assertEquals(204, response.statusCode());
    verify(bookingServiceMock).identifyOrCreateTelegramClient("12345678", "Natalia");
    verify(aiAssistantServiceMock).processChat("12345678", "Хочу записаться");
    verify(notificationServiceMock).sendResponse("12345678", "Пожалуйста, выберите мастера...");
  }
}
