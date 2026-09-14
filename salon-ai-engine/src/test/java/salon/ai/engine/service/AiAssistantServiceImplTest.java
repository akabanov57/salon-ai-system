package salon.ai.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.avaje.inject.BeanScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import salon.ai.engine.internal.service.DateTimeParser;
import salon.ai.engine.internal.service.LowLevelAiService;
import salon.api.exception.AiEngineException;
import salon.api.exception.StorageInfrastructureException;
import salon.api.model.Appointment;
import salon.api.model.AppointmentStatus;
import salon.api.model.CatalogService;
import salon.api.model.DialogueContext;
import salon.api.model.DialogueState;
import salon.api.model.LlamaResponse;
import salon.api.model.Master;
import salon.api.model.PlatformType;
import salon.api.model.ProcessMessageCommand;
import salon.api.service.AiAssistantService;
import salon.api.service.BookingService;
import salon.api.service.ChatMemoryService;

/**
 * <h2>DESIGN INTENT: Unit test suite for the AI Engine business boundary core bridge</h2>
 * This test isolates the {@link AiAssistantServiceImpl} from the underlying LangChain4j execution
 * engine networks and physical relational databases using light dynamic Mockito proxies.
 */
@DisplayName("Архитектурный Unit-тест оркестратора AiAssistantServiceImpl")
class AiAssistantServiceImplTest {

  private static BeanScope beanScope;

  /**
   * Объявляем замещение низкоуровневого ИИ-агента (LangChain4j) тестовым моком
   */
  private static LowLevelAiService lowLevelAiServiceMock;
  /**
   * Объявляем замещение тестовым моком
   */
  private static ChatMemoryService chatMemoryMock;

  /**
   * Объявляем замещение тестовым моком.
   */
  private static BookingService bookingServiceMock;

  private static DateTimeParser datetimeParser;

  private static AiAssistantService aiAssistantService;

  private final String testPlatformId = "telegram_chat_888";
  private final PlatformType testPlatformType = PlatformType.TELEGRAM;
  private final String traceId = UUID.randomUUID().toString();

  @BeforeAll
  static void startPipeline() {
    // Чистая инициализация экземпляров перед сборкой контейнера зависимостей
    lowLevelAiServiceMock = Mockito.mock(LowLevelAiService.class);
    chatMemoryMock = Mockito.mock(ChatMemoryService.class);
    bookingServiceMock = Mockito.mock(BookingService.class);
    datetimeParser = Mockito.mock(DateTimeParser.class);

    beanScope = BeanScope.builder()
        .beans(lowLevelAiServiceMock, chatMemoryMock,
            bookingServiceMock, datetimeParser) // Provide our mock definitions
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
    reset(lowLevelAiServiceMock, chatMemoryMock, datetimeParser);

    // Извлекаем протестированный синглтон из скомпилированного скоупа Avaje Inject
    aiAssistantService = beanScope.get(AiAssistantService.class);
  }

  /**
   * <h3>Сценарий 1: Сквозная обработка команды отмены реальной стейт-машиной</h3>
   * <p>
   * <b>Что дано (Given):</b> Входящая команда на отмену записи. Репозиторий СУБД возвращает
   * пустой контекст диалога (клиент новый). ИИ-парсер Llama3 извлекает отмену.
   * </p>
   * <p>
   * <b>Какое действие выполняется (When):</b> Команда передается в метод {@code processChat}.
   * </p>
   * <p>
   * <b>Что проверяется (Then):</b> Оркестратор должен асинхронно прогнать данные через
   * внедренную реальную стейт-машину. Проверяется, что стейт-машина отработала свое реальное
   * поведение (вернула текст "Запись отменена." и сбросила стейт в INIT), а репозиторий закоммитил
   * этот результат.
   * </p>
   */
  @Test
  @DisplayName("Сценарий 1: Сквозная обработка команды отмены реальной стейт-машиной")
  void shouldSuccessfullyProcessCommandThroughRealStateMachine() {
    // Given
    ProcessMessageCommand command = new ProcessMessageCommand(
        traceId,
        testPlatformType,
        testPlatformId,
        "Guest",
        "Отмени всё, пожалуйста"
    );

    // Симулируем, что в СУБД пока нет сохраненной сессии (клиент новый)
    when(chatMemoryMock.findContextByClientId(testPlatformType, testPlatformId))
        .thenReturn(Optional.empty());

    // Имитируем, что Llama3 определил интент отмены и заполнил пустые слоты
    LlamaResponse mockLlamaResponse = new LlamaResponse("cancel_or_reset",
        new LlamaResponse.Slots(null, null, null));
    when(lowLevelAiServiceMock.extractIntentAndSlots("Отмени всё, пожалуйста"))
        .thenReturn(mockLlamaResponse);

    // When: Выполняем оркестрацию. Внутри сработает РЕАЛЬНЫЙ код SalonStateMachineService
    String reply = aiAssistantService.processChat(command);

    // Then: Проверяем реальный бизнес-результат работы стейт-машины
    assertNotNull(reply);
    assertEquals("Запись отменена.", reply);

    // Проверяем, что оркестратор сохранил в базу данных чистый инициализированный стейт INIT
    DialogueContext expectedTargetContext = DialogueContext.createNew(testPlatformId);
    verify(chatMemoryMock, times(1)).saveContext(testPlatformType, testPlatformId,
        expectedTargetContext);
  }

  /**
   * <h3>Сценарий 2: Изоляция и трансляция аппаратных сбоев PostgreSQL</h3>
   * <p>
   * <b>Что дано (Given):</b> Мок репозитория при попытке чтения выбрасывает
   * низкоуровневое исключение СУБД {@link StorageInfrastructureException}.
   * </p>
   * <p>
   * <b>Какое действие выполняется (When):</b> Команда передается на обработку оркестратору.
   * </p>
   * <p>
   * <b>Что проверяется (Then):</b> Оркестратор обязан перехватить внутреннее исключение базы
   * данных
   * внутри виртуального потока, подавить технические детали и выбросить контролируемый
   * верхнеуровневый доменное исключение {@link AiEngineException}.
   * </p>
   */
  @Test
  @DisplayName("Сценарий 2: Трансляция StorageInfrastructureException в доменный AiEngineException")
  void shouldMapStorageExceptionToAiEngineException() {
    // Given
    ProcessMessageCommand command = new ProcessMessageCommand(
        traceId,
        testPlatformType,
        testPlatformId,
        "Guest",
        "Привет"
    );

    // Имитируем падение линка к базе данных на этапе восстановления контекста
    when(chatMemoryMock.findContextByClientId(testPlatformType, testPlatformId))
        .thenThrow(new StorageInfrastructureException("HikariCP: Connection timeout"));

    // When & Then
    AiEngineException exception = assertThrows(AiEngineException.class, () ->
        aiAssistantService.processChat(command)
    );

    assertInstanceOf(StorageInfrastructureException.class, exception.getCause());
    assertTrue(exception.getMessage().contains("Внутренняя ошибка базы данных"));

    // Гарантируем, что контур нейросети не вызывался из-за падения на раннем шаге
    verify(lowLevelAiServiceMock, never()).extractIntentAndSlots(any());
  }

  /**
   * <h3>Сценарий 3: Полноценный многотуровый разговорный цикл (Happy Path)</h3>
   *
   * <p><b>Бизнес-флоу диалога:</b>
   * <ul>
   *   <li><b>Тур 1:</b> Клиент здоровается и запрашивает стрижку на конкретный день. Система находит услугу в каталоге,
   *       подбирает список свободных специалистов на этот день через СУБД и просит выбрать мастера (стейт {@code STYLIST_PREFERENCE}).</li>
   *   <li><b>Тур 2:</b> Клиент выбирает конкретного мастера из списка. Система сверяет расписание мастера и автоматически
   *       запускает сквозной перебор окон, находя свободный слот. Бронируется холдинг {@code AI_PENDING} (стейт {@code CONFIRMATION_PENDING}).</li>
   * </ul>
   * </p>
   */
  @Test
  @DisplayName("Сценарий 3: Полноценный многотуровый разговорный цикл (Happy Path) до фиксации бронирования")
  void shouldExecuteFullMultiTurnHappyPathBookingFlow() {
    LocalDate tomorrow = LocalDate.now().plusDays(1);
    LocalTime targetTime = LocalTime.of(14, 0);
    LocalDateTime appointmentDateTime = LocalDateTime.of(tomorrow, targetTime);

    ArgumentCaptor<DialogueContext> contextCaptor = ArgumentCaptor.forClass(DialogueContext.class);

    // =================================================================================
    // ТУР №1: КЛИЕНТ ЗАПРАШИВАЕТ ТОЧНУЮ УСЛУГУ НА ЗАВТРА К 14:00 (СТРОГОЕ СОВПАДЕНИЕ)
    // =================================================================================
    ProcessMessageCommand turn1Command = new ProcessMessageCommand(
        traceId, testPlatformType, testPlatformId, "Guest",
        "Хочу записаться на Мужская стрижка завтра к 14:00"
    );

    when(chatMemoryMock.findContextByClientId(testPlatformType, testPlatformId))
        .thenReturn(Optional.empty());

    LlamaResponse turn1Llama = new LlamaResponse("book_appointment",
        new LlamaResponse.Slots("Мужская стрижка", null, "завтра в 14:00"));
    when(lowLevelAiServiceMock.extractIntentAndSlots("Хочу записаться на Мужская стрижка завтра к 14:00"))
        .thenReturn(turn1Llama);

    // ИСПРАВЛЕНО: Защищаем пулл виртуальных потоков от промахов Mockito по строковым ключам
    when(datetimeParser.parseRaw(anyString())).thenReturn(Optional.of(appointmentDateTime));

    List<CatalogService> serviceCatalog = List.of(
        new CatalogService("Мужская стрижка", 45, BigDecimal.valueOf(1500.0)));
    when(bookingServiceMock.searchServicesInCatalog("Мужская стрижка")).thenReturn(serviceCatalog);

    List<Master> freeMasters = List.of(
        new Master("elena_colorist", "Елена", "Иванова", "Top Colorist"));
    when(bookingServiceMock.getAvailableMastersForServiceInterval("Мужская стрижка", tomorrow,
        targetTime, targetTime.plusMinutes(45)))
        .thenReturn(freeMasters);

    // Выполняем Тур 1
    String reply1 = aiAssistantService.processChat(turn1Command);

    assertNotNull(reply1);
    assertTrue(reply1.contains("elena_colorist"), "Ответ обязан содержать псевдоним доступного мастера");
    assertTrue(reply1.contains("Кто вам больше подходит?"));

    verify(chatMemoryMock, times(1)).saveContext(eq(testPlatformType), eq(testPlatformId),
        contextCaptor.capture());
    DialogueContext contextAfterTurn1 = contextCaptor.getValue();

    assertEquals(DialogueState.STYLIST_PREFERENCE, contextAfterTurn1.currentState());
    assertEquals("Мужская стрижка", contextAfterTurn1.slots().service());
    assertNull(contextAfterTurn1.slots().stylist());

    // =================================================================================
    // ТУР №2: КЛИЕНТ ОТВЕЧАЕТ - "ДАВАЙТЕ К ЕЛЕНЕ"
    // =================================================================================
    ProcessMessageCommand turn2Command = new ProcessMessageCommand(
        traceId, testPlatformType, testPlatformId, "Guest", "Давайте к елене"
    );

    reset(chatMemoryMock);
    when(chatMemoryMock.findContextByClientId(testPlatformType, testPlatformId))
        .thenReturn(Optional.of(contextAfterTurn1));

    LlamaResponse turn2Llama = new LlamaResponse("book_appointment",
        new LlamaResponse.Slots(null, "elena_colorist", null));
    when(lowLevelAiServiceMock.extractIntentAndSlots("Давайте к елене"))
        .thenReturn(turn2Llama);

    when(bookingServiceMock.getAvailableMastersForServiceInterval("Мужская стрижка", tomorrow,
        targetTime, targetTime.plusMinutes(45)))
        .thenReturn(freeMasters);
    when(bookingServiceMock.searchServicesInCatalog("Мужская стрижка")).thenReturn(serviceCatalog);

    Appointment mockAppointment = new Appointment(
        "SB-2026-X79", testPlatformId, "elena_colorist", "Мужская стрижка",
        appointmentDateTime, 45, new BigDecimal("1500.00"), AppointmentStatus.AI_PENDING,
        LocalDateTime.now()
    );
    when(bookingServiceMock.tryAiBooking(testPlatformId, "elena_colorist", "Мужская стрижка",
        appointmentDateTime))
        .thenReturn(Optional.of(mockAppointment));

    // Выполняем Тур 2
    String reply2 = aiAssistantService.processChat(turn2Command);

    assertNotNull(reply2);
    assertTrue(reply2.contains("elena_colorist"));
    assertTrue(reply2.contains("Подтверждаете запись?"));

    ArgumentCaptor<DialogueContext> finalContextCaptor = ArgumentCaptor.forClass(DialogueContext.class);
    verify(chatMemoryMock, times(1)).saveContext(eq(testPlatformType), eq(testPlatformId),
        finalContextCaptor.capture());
    DialogueContext finalContext = finalContextCaptor.getValue();

    assertEquals(DialogueState.CONFIRMATION_PENDING, finalContext.currentState());
    assertEquals("elena_colorist", finalContext.slots().stylist());
    assertNotNull(finalContext.slots().confirmedDatetime());
  }

  /**
   * <h3>Тест под Раздел 2 Справочника: Неявная контекстная амбивалентность (Implicit String Mismatch)</h3>
   *
   * <p><b>Что дано (Given):</b>
   * Пользователь пишет общее слово {@code "стрижка"}. В базе заведена всего одна услуга,
   * но её название длиннее и детальнее — {@code "Женская стрижка модельная"}.
   * Размер возвращаемого списка равен 1, но точного совпадения строк нет (isExactMatch == false).</p>
   *
   * <p><b>Какое действие выполняется (When):</b>
   * Команда с текстом передается на вход оркестратору.</p>
   *
   * <p><b>Что проверяется (Then):</b>
   * Система ОБЯЗАНА автоматически нормализовать имя услуги, подставив официальное
   * значение из СУБД, и продвинуть диалог вперед в состояние {@code AVAILABILITY_MATCH}.</p>
   */
  @Test
  @DisplayName("Сценарий 4: Перехват неявной контекстной амбивалентности при частичном совпадении строк")
  void shouldInterceptImplicitAmbiguityWhenServiceFoundButNameIsNotExactMatch() {
    // Given
    ProcessMessageCommand command = new ProcessMessageCommand(
        traceId, testPlatformType, testPlatformId, "Guest", "Хочу записаться на стрижку"
    );

    when(chatMemoryMock.findContextByClientId(testPlatformType, testPlatformId))
        .thenReturn(Optional.empty());

    LlamaResponse mockLlamaResponse = new LlamaResponse("book_appointment",
        new LlamaResponse.Slots("стрижка", null, null));
    when(lowLevelAiServiceMock.extractIntentAndSlots("Хочу записаться на стрижку"))
        .thenReturn(mockLlamaResponse);

    List<CatalogService> partialMatchList = List.of(
        new CatalogService("Женская стрижка модельная", 60, BigDecimal.valueOf(2000.00))
    );
    // Защищаем повторный вызов каталога после авто-нормализации
    when(bookingServiceMock.searchServicesInCatalog(anyString())).thenReturn(partialMatchList);

    // When
    String reply = aiAssistantService.processChat(command);

    // Then: Верифицируем, что система перешла к подбору окон для нормализованной услуги
    assertNotNull(reply);
    assertTrue(reply.contains("Женская стрижка модельная"), "Ответ должен содержать нормализованное имя услуги");
    assertTrue(reply.contains("На какой день и время вам подобрать свободные окна"), "Система должна продвинуться к шагу 2.5");

    ArgumentCaptor<DialogueContext> contextCaptor = ArgumentCaptor.forClass(DialogueContext.class);
    verify(chatMemoryMock, times(1)).saveContext(eq(testPlatformType), eq(testPlatformId), contextCaptor.capture());
    DialogueContext savedContext = contextCaptor.getValue();

    assertEquals(DialogueState.AVAILABILITY_MATCH, savedContext.currentState(), "Стейт должен переключиться в AVAILABILITY_MATCH");
    assertEquals("Женская стрижка модельная", savedContext.slots().service(), "Имя услуги должно перезаписаться в памяти СУБД");
  }

  /**
   * <h3>Тест под Раздел 3 Справочника: Хронологическая (временная) амбивалентность (Chronological Ambiguity)</h3>
   *
   * <p><b>Что дано (Given):</b>
   * Пользователь хочет перенести или выбрать время, но пишет размытую фразу {@code "на попозже"}.
   * Наш жесткий {@code DateTimeParser} не может распарсить этот текст в фиксированный LocalDateTime
   * и возвращает {@code Optional.empty()}. Модель Llama3.2 правильно определяет интент
   * как {@code "change_datetime_ambiguous"}.</p>
   *
   * <p><b>Какое действие выполняется (When):</b>
   * Команда передается оркестратору.</p>
   *
   * <p><b>Что проверяется (Then):</b>
   * Стейт-машина должна поймать этот интент на Шаге 5, перевести сессию в состояние
   * {@code CLARIFY_INTENT} и выдать клиенту строгую системную инструкцию-вопрос для
   * конкретизации временных границ.</p>
   */
  @Test
  @DisplayName("Сценарий 5: Перехват хронологической амбивалентности при размытом указании времени")
  void shouldInterceptChronologicalAmbiguityWhenParserReturnsEmptyOnVagueTime() {
    // Given: Сессия уже зафиксировала Мужскую стрижку и мастера на этапе подтверждения
    DialogueContext existingContext = new DialogueContext(
        DialogueState.CONFIRMATION_PENDING,
        new DialogueContext.Slots("Мужская стрижка", "elena_colorist", "завтра в 14:00", null),
        new DialogueContext.Metadata(2, 0, testPlatformId)
    );

    ProcessMessageCommand command = new ProcessMessageCommand(
        traceId, testPlatformType, testPlatformId, "Guest", "Давайте перенесем на попозже"
    );

    //  ФИКС: Используем точное доменное имя метода findContext вместо findContextByClientId!
    when(chatMemoryMock.findContextByClientId(testPlatformType, testPlatformId))
        .thenReturn(Optional.of(existingContext));

    LlamaResponse mockLlamaResponse = new LlamaResponse("change_datetime_ambiguous",
        new LlamaResponse.Slots(null, null, "попозже"));
    when(lowLevelAiServiceMock.extractIntentAndSlots("Давайте перенесем на попозже"))
        .thenReturn(mockLlamaResponse);

    // Точечное переопределение парсера для строки "попозже" внутри пула потоков
    when(datetimeParser.parseRaw("попозже")).thenReturn(Optional.empty());

    List<CatalogService> serviceCatalog = List.of(
        new CatalogService("Мужская стрижка", 45, BigDecimal.valueOf(1500.0))
    );
    when(bookingServiceMock.searchServicesInCatalog(anyString())).thenReturn(serviceCatalog);

    // When: Запускаем оркестрацию
    String reply = aiAssistantService.processChat(command);

    // Then: Теперь контекст извлечен правильно, стейт равен CONFIRMATION_PENDING, и Шаг 5 сработает!
    assertNotNull(reply);
    assertTrue(reply.contains("Вы хотите выбрать другую дату для этой записи или полностью отменить её?"),
        "Ответ сервера должен содержать вопрос-уточнение о переносе или отмене визита");

    ArgumentCaptor<DialogueContext> contextCaptor = ArgumentCaptor.forClass(DialogueContext.class);
    verify(chatMemoryMock, times(1)).saveContext(eq(testPlatformType), eq(testPlatformId), contextCaptor.capture());
    DialogueContext savedContext = contextCaptor.getValue();

    assertEquals(DialogueState.CLARIFY_INTENT, savedContext.currentState(), "Система должна зайти в буфер CLARIFY_INTENT");
  }
}
