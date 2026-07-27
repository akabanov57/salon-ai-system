package salon.web.http.web;

import io.avaje.http.api.Body;
import io.avaje.http.api.Controller;
import io.avaje.http.api.Path;
import io.avaje.http.api.Post;
import io.avaje.inject.External;
import io.avaje.jsonb.Json;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import salon.api.model.Client;
import salon.api.service.AiAssistantService;
import salon.api.service.BookingService;
import salon.api.service.NotificationService;

/**
 * Compile-time REST controller handling incoming messaging webhooks.
 * Mapped natively via avaje-http routing processors.
 */
@Controller
@Path("/api/v1/webhooks")
public class WebhookController {

  private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

  private final BookingService bookingService;

  private final AiAssistantService aiAssistantService; // INJECT THE AI PORT INTERFACE

  private final NotificationService notificationService; // ДОБАВЛЯЕМ В КЛАСС

  @Inject
  public WebhookController(@External BookingService bookingService, @External AiAssistantService aiAssistantService, @External NotificationService notificationService) {
    this.bookingService = bookingService;
    this.aiAssistantService = aiAssistantService;
    this.notificationService = notificationService;
  }

  /**
   * Data Transfer Object representing an incoming raw text event.
   */
  @Json
  public record IncomingMessageDto(
      String platformId,
      String platformType,
      String firstName,
      String text) {}

  @Post("/message")
  public void handleIncomingMessage(@Body IncomingMessageDto payload) {
    log.info("Processing incoming platform chat payload from stream handle: {}", payload.platformId());

    if ("TELEGRAM".equalsIgnoreCase(payload.platformType())) {
      // 1. Идентифицируем или регистрируем профиль клиента
      Client client = bookingService.identifyOrCreateTelegramClient(payload.platformId(), payload.firstName());
      log.info("Routing conversational transaction path for client profile ID: {}", client.id());

      // 2. Передаем текст сообщения в ИИ-движок и получаем текстовый ответ
      String aiReplyText = aiAssistantService.processChat(payload.platformId(), payload.text());
      log.info("AI orchestration finalized successfully. Reply generated.");

      // 3. ИСПРАВЛЕНО: Вызываем отправку ответа обратно в Telegram!
      notificationService.sendResponse(payload.platformId(), aiReplyText);
      log.info("Response dispatched to notification service pipeline.");

    } else {
      log.warn("Received unhandled platform messenger layout type: {}", payload.platformType());
    }
  }

}
