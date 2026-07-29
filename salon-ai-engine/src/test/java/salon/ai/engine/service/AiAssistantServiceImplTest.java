package salon.ai.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import salon.ai.engine.internal.service.LowLevelAiService;
import salon.api.model.Client;
import salon.api.service.BookingService;

/**
 * <h2>DESIGN INTENT: Unit test suite for the AI Engine business boundary core bridge</h2>
 * This test isolates the {@link AiAssistantServiceImpl} from the underlying LangChain4j execution
 * engine networks and physical relational databases using light dynamic Mockito proxies.
 */
class AiAssistantServiceImplTest {

  private LowLevelAiService lowLevelAiServiceMock;
  private AiAssistantServiceImpl aiAssistantService;

  @BeforeEach
  void setUp() {
    // Instantiate lightweight mock proxy stubs for required constructor arguments
    BookingService bookingServiceMock = Mockito.mock(BookingService.class);
    lowLevelAiServiceMock = Mockito.mock(LowLevelAiService.class);

    // FIX: Stub the database profile lookup so it never returns a dangerous null bridge pointer
    Client dummyClient = new Client(1L, "Ivan", null, null, "telegram_chat_555", null, 0, LocalDateTime.now());
    when(bookingServiceMock.identifyOrCreateTelegramClient(anyString(), any()))
        .thenReturn(dummyClient);

    aiAssistantService = new AiAssistantServiceImpl(bookingServiceMock, lowLevelAiServiceMock);
  }

  /**
   * <p><b>VERIFICATION OBJECTIVES:</b>
   * <ul>
   *   <li>Verify that invoking {@code processChat} correctly delegates execution down into the internal low-level framework agent {@link LowLevelAiService#chat(String, String)}.</li>
   *   <li>Verify that parameters ({@code platformId}, {@code userMessageText}) are securely passed deep into the stateful execution tracks without distortions or truncations.</li>
   *   <li>Verify that the final text string payload computed by the internal AI model is successfully returned back intact to the calling boundary component.</li>
   * </ul>
   * </p>
   */
  @Test
  void shouldSuccessfullyDelegateChatParametersToInternalLowLevelAiAgent() {
    // Arrange
    final String samplePlatformId = "telegram_chat_555";
    final String sampleUserText = "Привет, к какому мастеру можно записаться на стрижку?";
    final String expectedAiReply = "Привет! У нас свободны Елена и Наталья. К кому вас записать?";

    // Stub the low-level agent to return our reply for any incoming text string
    when(lowLevelAiServiceMock.chat(anyString(), anyString()))
        .thenReturn(expectedAiReply);

    // Act
    final String actualReply = aiAssistantService.processChat(samplePlatformId, sampleUserText);

    // Assert Step 1: Verify the unmodified AI text reply traverses the layer boundary cleanly
    assertEquals(expectedAiReply, actualReply, "The business bridge adapter must pass back the text response unmodified.");

    // Assert Step 2: Use an ArgumentCaptor to intercept and inspect the exact metadata string injected on Line 62
    final ArgumentCaptor<String> textPayloadCaptor = ArgumentCaptor.forClass(String.class);
    verify(lowLevelAiServiceMock).chat(Mockito.eq(samplePlatformId), textPayloadCaptor.capture());

    final String capturedActualMessage = textPayloadCaptor.getValue();

    // Explicitly assert that the system metadata context and client fields are formatted correctly
    assertTrue(capturedActualMessage.contains("Client Database ID is 1"));
    assertTrue(capturedActualMessage.contains("First Name: Ivan"));
    assertTrue(capturedActualMessage.contains(sampleUserText));
  }

}
