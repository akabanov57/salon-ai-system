package salon.ai.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.avaje.inject.BeanScope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import salon.ai.engine.internal.service.LowLevelAiService;
import salon.api.exception.AiEngineException;
import salon.api.model.PlatformType;
import salon.api.model.ProcessMessageCommand;
import salon.api.service.AiAssistantService;
import salon.api.service.BookingService;

/**
 * <h2>DESIGN INTENT: Unit test suite for the AI Engine business boundary core bridge</h2>
 * This test isolates the {@link AiAssistantServiceImpl} from the underlying LangChain4j execution
 * engine networks and physical relational databases using light dynamic Mockito proxies.
 */
class AiAssistantServiceImplTest {

  private static BeanScope beanScope;

  // Объявляем замещение низкоуровневого ИИ-агента (LangChain4j) тестовым моком
  private static LowLevelAiService lowLevelAiServiceMock;
  private static BookingService bookingServiceMock;

  private AiAssistantService aiAssistantService;

  @BeforeAll
  static void startPipeline() {
    // Чистая инициализация экземпляров перед сборкой контейнера зависимостей
    lowLevelAiServiceMock = Mockito.mock(LowLevelAiService.class);
    bookingServiceMock = Mockito.mock(BookingService.class);

    beanScope = BeanScope.builder()
        //.modules(new salon.ai.engine.EngineModule()) // Load the AI engine's generated module
        .beans(lowLevelAiServiceMock, bookingServiceMock) // Provide our mock definitions
        .build();
  }

  @AfterAll
  static void stopPipeline() {
    if (beanScope != null) {
      beanScope.close();
    }
  }

  @BeforeEach
  void setUp() {
    // Намёртво сбрасываем конфигурации вызовов моков перед каждым тест-кейсом
    Mockito.reset(lowLevelAiServiceMock, bookingServiceMock);

    // Извлекаем протестированный синглтон из скомпилированного скоупа Avaje Inject
    aiAssistantService = beanScope.get(AiAssistantService.class);
  }

  /**
   * <h3>Тест 1: Успешный prompt-синтез ответа при отзывчивом ИИ-ядре (Happy Path)</h3>
   * <p><b>Бизнес-контекст:</b> Пользователь отправляет сообщение в чат. Сервис обязан перенаправить
   * его
   * низкоуровневому агенту и вернуть текстовый ответ клиенту мессенджера без искажений [Strict
   * Grounding].</p>
   */
  @Test
  void shouldSuccessfullyProcessChatWhenAiEngineIsResponsive() {
    // Arrange
    String sampleResponse = "Отличный выбор! 💇‍♀️ Записала вас к Елене.";
    when(lowLevelAiServiceMock.chat(anyString(), anyString())).thenReturn(sampleResponse);

    // Формируем чистую доменную команду с валидными параметрами
    ProcessMessageCommand command = new ProcessMessageCommand(
        "TX-AI-TEST-001",
        PlatformType.TELEGRAM,
        "12345678",
        "Natalia",
        "Хочу записаться к Елене на стрижку"
    );

    assertNotNull(aiAssistantService,
        "DI-контейнер обязан успешно инициализировать AiAssistantService.");

    // Act: Прогоняем сквозной вызов через виртуальные потоки нашего оркестратора
    String actualResponse = aiAssistantService.processChat(command);

    // Assert: Верифицируем точность передачи аргументов и отсутствие текстовых искажений
    assertEquals(sampleResponse, actualResponse,
        "Итоговый ответ должен в точности соответствовать сгенерированному ИИ тексту.");
    verify(lowLevelAiServiceMock, times(1)).chat("12345678", "Хочу записаться к Елене на стрижку");
  }

  /**
   * <h3>Тест 2: Трансляция внутренних технических сбоев в доменные исключения (Error
   * Handling)</h3>
   * <p><b>Бизнес-контекст:</b> Если удаленный сервер Ollama недоступен или разорвал сетевое
   * соединение,
   * техническая ошибка должна быть перехвачена и превращена в безопасное доменное исключение
   * AiEngineException [Strict Grounding].</p>
   */
  @Test
  void shouldThrowAiEngineExceptionWhenLowLevelAgentCrashes() {
    // Arrange
    when(lowLevelAiServiceMock.chat(anyString(), anyString()))
        .thenThrow(new RuntimeException("Connection to Ollama failed or timed out"));

    ProcessMessageCommand command = new ProcessMessageCommand(
        "TX-AI-FAIL-002",
        PlatformType.TELEGRAM,
        "12345678",
        "Natalia",
        "Тестовый сбой"
    );

    // Act & Assert: Проверяем принудительное срабатывание барьера защиты
    AiEngineException exception = assertThrows(AiEngineException.class,
        () -> aiAssistantService.processChat(command),
        "При падении ИИ-движка сервис обязан выбросить специализированное исключение AiEngineException.");

    assertTrue(exception.getMessage().contains("Внутренний сбой фабрики ИИ"),
        "Сообщение об ошибке должно содержать понятный доменный контекст.");
  }

}
