package salon.ai.engine.service;

import io.avaje.inject.External;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import salon.ai.engine.internal.service.LowLevelAiService;
import salon.api.model.Client;
import salon.api.service.AiAssistantService;
import salon.api.service.BookingService;

@Singleton
final class AiAssistantServiceImpl implements AiAssistantService {

  private static final Logger log = LoggerFactory.getLogger(AiAssistantServiceImpl.class);

  private final BookingService bookingService;

  private final LowLevelAiService aiService;

  @Inject
  AiAssistantServiceImpl(@External BookingService bookingService, LowLevelAiService aiService) {
    this.bookingService = bookingService;
    this.aiService = aiService;
  }

  @Override
  public String processChat(String telegramId, String userMessage) {
    log.info("Processing conversational routing trace context for user handle: {}", telegramId);

    final Client client = bookingService.identifyOrCreateTelegramClient(telegramId, "Valued Client");

    final String contextualPayload = String.format("[System Metadata Context: Client Database ID is %d. First Name: %s]\nUser Input: %s",
        client.id(), client.firstName(), userMessage);

    final String aiResponse = aiService.chat(telegramId, contextualPayload);

    log.debug("AI conversational engine stream processing finalized successfully.");
    return aiResponse;
  }
}
