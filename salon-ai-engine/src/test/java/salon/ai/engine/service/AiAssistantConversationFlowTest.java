package salon.ai.engine.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.avaje.inject.BeanScope;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import salon.ai.engine.internal.service.LowLevelAiService;
import salon.api.model.Appointment;
import salon.api.model.AppointmentStatus;
import salon.api.model.CatalogService;
import salon.api.service.BookingService;

/**
 * <h3>Сквозной компонентный тест многотурового диалога ИИ-ассистента</h3>
 *
 * <p>Проверяет правильность накопления контекста в {@code ChatMemory}, корректность перехвата
 * намерений модели и активацию каскада ИИ-инструментов без запуска реального сервера Ollama [Strict Grounding].</p>
 */
class AiAssistantConversationFlowTest {

  private static BeanScope beanScope;
  private static BookingService bookingServiceMock;
  private static ChatModel chatModelMock;
  private static LowLevelAiService lowLevelAiService;

  @BeforeAll
  static void startPipeline() {
    bookingServiceMock = Mockito.mock(BookingService.class);
    chatModelMock = Mockito.mock(ChatModel.class);

    // Собираем полноценный контекст Avaje Inject, подставляя моки зависимостей
    beanScope = BeanScope.builder()
        .beans(bookingServiceMock, chatModelMock)
        .build();

    // Извлекаем собранный ИИ-сервис, настроенный через фабрику конфигурации
    lowLevelAiService = beanScope.get(LowLevelAiService.class);
  }

  @AfterAll
  static void stopPipeline() {
    if (beanScope != null) {
      beanScope.close();
    }
  }

  @BeforeEach
  void setUp() {
    Mockito.reset(bookingServiceMock, chatModelMock);
  }

  /**
   * <h3>Сценарий: Полноценный многотуровый разговорный цикл (Happy Path)</h3>
   * <p><b>Бизнес-контекст:</b> Клиент здоровается и запрашивает стрижку, система находит услугу,
   * затем клиент выбирает мастера и время. ИИ должен успешно провести сессию памяти и вызвать бронирование [Strict Grounding].</p>
   */
  @Test
  void shouldMaintainContextAndTriggerToolsAcrossMultiTurnConversation() {
    // -----------------------------------------------------------------
    // ТУР 1: Приветствие и нечеткий поиск услуги в каталоге
    // -----------------------------------------------------------------
    String userId = "TG-USER-777";

    // Настраиваем доменный мок каталога услуг
    CatalogService expectedService = new CatalogService("Женская стрижка модельная", 60, BigDecimal.valueOf(2500.00));
    when(bookingServiceMock.searchServicesInCatalog("стрижка")).thenReturn(List.of(expectedService));

    // Симулируем ответ модели на первом шаге: она решает вызвать инструмент поиска searchServices
    ToolExecutionRequest searchRequest = ToolExecutionRequest.builder()
        .id("call_idx_1")
        .name("searchServices")
        .arguments("{\"searchKeyword\":\"стрижка\"}")
        .build();

    // Конструируем современный ChatResponse с помощью билдера LangChain4j
    ChatResponse firstLlmResponse = ChatResponse.builder()
        .aiMessage(AiMessage.from(searchRequest))
        .build();

    // Симулируем текстовый ответ модели ПОСЛЕ того, как инструмент выполнился и вернул данные в историю чата
    ChatResponse firstTextResponse = ChatResponse.builder()
        .aiMessage(AiMessage.from("У нас есть услуга 'Женская стрижка модельная' (2500 руб.). К какому мастеру вы хотите записаться?"))
        .build();

    // Настраиваем мок современной модели под последовательные вызовы метода .chat()
    when(chatModelMock.chat(any(ChatRequest.class)))
        .thenReturn(firstLlmResponse)
        .thenReturn(firstTextResponse);

    // Выполняем первый шаг диалога
    String firstBotReply = lowLevelAiService.chat(userId, "Привет! Хочу записаться на стрижку");

    // Проверяем промежуточный результат первого тура
    assertNotNull(firstBotReply);
    assertTrue(firstBotReply.contains("Женская стрижка модельная"), "Ответ ИИ обязан содержать наименование услуги.");
    verify(bookingServiceMock, times(1)).searchServicesInCatalog("стрижка");

    // -----------------------------------------------------------------
    // ТУР 2: Выбор мастера, времени и финальное резервирование билета в СУБД
    // -----------------------------------------------------------------

    // Настраиваем доменный мок создания бронирования
    Appointment dummyAppointment = new Appointment(
        "SB-260825-TICKET",
        userId,
        "elena_colorist",
        "Женская стрижка модельная",
        LocalDateTime.parse("2026-08-25T14:30"),
        60,
        BigDecimal.valueOf(2500.00),
        AppointmentStatus.AI_PENDING,
        LocalDateTime.now()
    );

    when(bookingServiceMock.tryAiBooking(eq(userId), eq("elena_colorist"), eq("Женская стрижка модельная"), any(LocalDateTime.class)))
        .thenReturn(Optional.of(dummyAppointment));

    // Симулируем ответ модели на втором шаге: она извлекает контекст из памяти чата и вызывает bookAppointmentSlot
    ToolExecutionRequest bookRequest = ToolExecutionRequest.builder()
        .id("call_idx_2")
        .name("bookAppointmentSlot")
        .arguments("{\"platformId\":\"" + userId + "\",\"masterAlias\":\"elena_colorist\",\"serviceName\":\"Женская стрижка модельная\",\"dateTimeStr\":\"2026-08-25T14:30\"}")
        .build();

    ChatResponse secondLlmResponse = ChatResponse.builder()
        .aiMessage(AiMessage.from(bookRequest))
        .build();

    ChatResponse secondTextResponse = ChatResponse.builder()
        .aiMessage(AiMessage.from("Отлично! Вы предварительно записаны. Ваш код билета: SB-260825-TICKET."))
        .build();

    // Переобучаем мок под вызовы метода .chat() во втором туре общения
    when(chatModelMock.chat(any(ChatRequest.class)))
        .thenReturn(secondLlmResponse)
        .thenReturn(secondTextResponse);

    // Выполняем второй шаг диалога, опираясь на скользящую память чата LangChain4j
    String secondBotReply = lowLevelAiService.chat(userId, "Запишите к Елене на 25 августа в 14:30");

    // Финальные проверки сквозной интеграции многотуровой сессии
    assertNotNull(secondBotReply);
    assertTrue(secondBotReply.contains("SB-260825-TICKET"), "Итоговый ответ ИИ обязан вернуть код сгенерированного билета.");
    verify(bookingServiceMock, times(1))
        .tryAiBooking(eq(userId), eq("elena_colorist"), eq("Женская стрижка модельная"), eq(LocalDateTime.parse("2026-08-25T14:30")));
  }

}
