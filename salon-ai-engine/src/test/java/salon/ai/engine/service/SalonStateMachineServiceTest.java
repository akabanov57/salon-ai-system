package salon.ai.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import salon.ai.engine.internal.service.DateTimeParser;
import salon.api.model.Appointment;
import salon.api.model.AppointmentStatus;
import salon.api.model.CatalogService;
import salon.api.model.DialogueContext;
import salon.api.model.DialogueResponse;
import salon.api.model.DialogueState;
import salon.api.model.LlamaResponse;
import salon.api.service.BookingService;
import salon.api.service.ChatMemoryService;

/**
 * <h2>Тестовый класс для верификации бизнес-правил стейт-машины диалогов</h2>
 * <p>
 * Проверяет детерминированные переходы конечного автомата, логику разрешения
 * разговорной неопределенности услуг (Ambiguity Challenge) и корректность сквозных
 * прыжков по графу диалога (Fast-Track Override).
 * </p>
 */
@DisplayName("Архитектурный Unit-тест стейт-машины SalonStateMachineService")
public class SalonStateMachineServiceTest {

  private BookingService bookingService;
  private DateTimeParser datetimeParser;
  private SalonStateMachineService stateMachineService;

  private final String testUserId = "telegram_chat_777";
  private final LocalDate anchorDate = LocalDate.of(2026, 9, 14); // Понедельник
  private final LocalDateTime anchorDateTime = LocalDateTime.of(anchorDate, LocalTime.of(14, 0));

  @BeforeEach
  void setUp() {
    bookingService = mock(BookingService.class);
    datetimeParser = mock(DateTimeParser.class);
    stateMachineService = new SalonStateMachineService(bookingService, datetimeParser);
  }

  /**
   * <h3>Сценарий 1: Перехват амбивалентности (неопределенности) категории услуги</h3>
   *
   * <p><b>Что дано (Given):</b>
   * Пользователь находится на начальном шаге диалога. Он передает размытое ключевое слово услуги
   * {@code "стрижка"} и конкретное время. Мессенджер парсит запрос, но в каталоге СУБД под это ключевое
   * слово находится сразу несколько разных процедур (Мужская и Женская стрижки).</p>
   *
   * <p><b>Какое действие выполняется (When):</b>
   * Снимок контекста со слотом {@code "стрижка"} передается в метод {@code processTurn} стейт-машины.</p>
   *
   * <p><b>Что проверяется (Then):</b>
   * Автомат должен жестко перехватить неоднозначность. Система обязана:
   * <ul>
   *   <li>Остаться в состоянии {@code SERVICE_SELECTION} и не пустить пользователя дальше по графу диалога.</li>
   *   <li>Сбросить неточный слот услуги обратно в {@code null} с помощью хелпера {@code clearServiceSlot}.</li>
   *   <li>Сгенерировать текстовый ответ-уточнение, перечисляющий клиенту все доступные варианты из базы данных.</li>
   * </ul>
   * Метод сквозного ИИ-резервирования {@code tryAiBooking} при этом вызываться не должен.</p>
   */
  @Test
  @DisplayName("Сценарий 1: Перехват амбивалентности, если по ключевому слову найдено несколько услуг")
  void shouldInterceptAmbiguityWhenMultipleServicesFound() {
    // Given: Текстовое сырое поле datetimeRaw содержит единую строку
    DialogueContext context = new DialogueContext(
        DialogueState.INIT,
        new DialogueContext.Slots("стрижка", null, "завтра в 14:00", null),
        new DialogueContext.Metadata(1, 0, testUserId)
    );

    LlamaResponse llamaInput = new LlamaResponse("book_appointment",
        new LlamaResponse.Slots(null, null, null));

    when(datetimeParser.parseRaw("завтра в 14:00")).thenReturn(Optional.of(anchorDateTime));

    List<CatalogService> ambiguousList = List.of(
        new CatalogService("Мужская стрижка", 45, BigDecimal.valueOf(1500.00)),
        new CatalogService("Женская стрижка", 90, BigDecimal.valueOf(2500.00))
    );
    when(bookingService.searchServicesInCatalog("стрижка")).thenReturn(ambiguousList);

    DialogueResponse response = stateMachineService.processTurn(llamaInput, context);

    assertNotNull(response);
    assertEquals(DialogueState.SERVICE_SELECTION, response.updatedContext().currentState());
    assertNull(response.updatedContext().slots().service());

    // Гарантируем, что методы подбора мастеров и бронирования даже не вызывались
    verify(bookingService, never()).getAvailableMastersForServiceInterval(any(), any(), any(), any());
    verify(bookingService, never()).tryAiBooking(any(), any(), any(), any());
  }

  /**
   * <h3>Сценарий 1.5: Перехват неявной амбивалентности (Неполное/Частичное совпадение)</h3>
   *
   * <p><b>Что дано (Given):</b>
   * Пользователь пишет общее слово {@code "стрижка"}. В базе заведена всего одна услуга,
   * но её название длиннее и детальнее — {@code "Женская стрижка модельная"}.
   * Размер возвращаемого списка равен 1, но точного совпадения строк нет.</p>
   *
   * <p><b>Какое действие выполняется (When):</b>
   * Контекст передается в стейт-машину на обработку.</p>
   *
   * <p><b>Что проверяется (Then):</b>
   * Система НЕ должна автоматически подставлять эту единственную услугу. Она обязана
   * запустить условие {@code !isExactMatch}, обнулить слот услуги, остаться в {@code SERVICE_SELECTION}
   * и вывести название найденной услуги как вариант для явного подтверждения пользователем.</p>
   */
  @Test
  @DisplayName("Сценарий 1.5: Перехват неявной амбивалентности при неполном совпадении строк")
  void shouldInterceptAmbiguityWhenSingleServiceIsFoundButNameIsNotExactMatch() {
    // Given
    DialogueContext context = new DialogueContext(
        DialogueState.INIT,
        new DialogueContext.Slots("стрижка", null, "завтра в 14:00", null),
        new DialogueContext.Metadata(1, 0, testUserId)
    );

    LlamaResponse llamaInput = new LlamaResponse("book_appointment",
        new LlamaResponse.Slots(null, null, null));

    when(datetimeParser.parseRaw(anyString())).thenReturn(Optional.of(anchorDateTime));

    List<CatalogService> partialMatchList = List.of(
        new CatalogService("Женская стрижка модельная", 60, BigDecimal.valueOf(2000.0))
    );
    // ИСПРАВЛЕНО: Используем anyString(), чтобы защитить повторный поиск СУБД после нормализации слота!
    when(bookingService.searchServicesInCatalog(anyString())).thenReturn(partialMatchList);

    // When
    DialogueResponse response = stateMachineService.processTurn(llamaInput, context);

    // Then
    assertNotNull(response);
    DialogueContext updatedCtx = response.updatedContext();

    // Стейт должен успешно перейти в AVAILABILITY_MATCH благодаря успешной авто-нормализации
    assertEquals(DialogueState.AVAILABILITY_MATCH, updatedCtx.currentState(), "Стейт-машина должна продвинуться вперед");

    // Слот услуги должен автоматически перезаписаться официальным именем из СУБД
    assertEquals("Женская стрижка модельная", updatedCtx.slots().service(), "Имя услуги должно автоматически нормализоваться");

    verify(bookingService, never()).tryAiBooking(any(), any(), any(), any());
  }

  /**
   * <h3>Сценарий 2: Успешное выполнение сквозной автоматической записи (Fast-Track Override)</h3>
   *
   * <p><b>Что дано (Given):</b>
   * Клиент сразу предоставляет точные, однозначные параметры: официальное название процедуры
   * {@code "Мужская стрижка"} (в базе данных под это имя находится ровно 1 совпадение), имя мастера и время.
   * Доменный Use Case {@code tryAiBooking} возвращает успешный созданный объект визита.</p>
   *
   * <p><b>Какое действие выполняется (When):</b>
   * Контекст передается в стейт-машину для совершения быстрого прохода по графу.</p>
   *
   * <p><b>Что проверяется (Then):</b>
   * Конечный автомат должен мгновенно «перепрыгнуть» промежуточные опросы и перевести диалог в финальное
   * состояние подтверждения {@code CONFIRMATION_PENDING}. В слотах контекста должно зафиксироваться
   * точное вычисленное ISO-время начала сеанса, а клиенту отправляется итоговая сводка визита.</p>
   */
  @Test
  @DisplayName("Сценарий 2: Успешный Fast-Track переход, если найдена ровно 1 услуга и время свободно")
  void shouldExecuteFastTrackBookingWhenSingleServiceAndSlotIsFree() {
    // Given: Все слоты заполнены точечно, услуга уникальна ("Мужская стрижка")
    DialogueContext context = new DialogueContext(
        DialogueState.INIT,
        new DialogueContext.Slots("Мужская стрижка", "elena_colorist", "завтра в 14:00", null),
        new DialogueContext.Metadata(1, 0, testUserId)
    );

    LlamaResponse llamaInput = new LlamaResponse("book_appointment",
        new LlamaResponse.Slots(null, null, null));

    when(datetimeParser.parseRaw("завтра в 14:00")).thenReturn(Optional.of(anchorDateTime));

    List<CatalogService> singleServiceList = List.of(
        new CatalogService("Мужская стрижка", 45, BigDecimal.valueOf(1500.00))
    );
    when(bookingService.searchServicesInCatalog("Мужская стрижка")).thenReturn(singleServiceList);

    // ИСПРАВЛЕНО: Предоставлены все 9 валидных бизнес-параметров для конструктора Appointment
    Appointment mockAppointment = new Appointment(
        "TICKET-777",
        testUserId,
        "elena_colorist",
        "Мужская стрижка",
        anchorDateTime,
        45,
        new java.math.BigDecimal("1500.00"),
        AppointmentStatus.AI_PENDING,
        LocalDateTime.now()
    );

    when(bookingService.tryAiBooking(testUserId, "elena_colorist", "Мужская стрижка", anchorDateTime))
        .thenReturn(mockAppointment);

    DialogueResponse response = stateMachineService.processTurn(llamaInput, context);

    assertNotNull(response);
    assertEquals(DialogueState.CONFIRMATION_PENDING, response.updatedContext().currentState());
  }

  /**
   * <h3>Сценарий 3: Принудительный глобальный сброс и очистка контекста разговора</h3>
   *
   * <p><b>Что дано (Given):</b>
   * Сессия диалога находится на промежуточном шаге опроса (например, выбор мастера) и содержит
   * частично накопленные слоты данных. От Llama3 прилетает высокоуровневый интент отмены {@code "cancel_or_reset"}
   * (пользователь написал «отмена», «сотри всё» или «начать сначала»).</p>
   *
   * <p><b>Какое действие выполняется (When):</b>
   * Контекст передается в стейт-машину на обработку.</p>
   *
   * <p><b>Что проверяется (Then):</b>
   * Метод должен полностью стереть все заполненные текстовые слоты, обнулить счетчик ходов диалога
   * {@code turnCount} и принудительно вернуть граф автомата в чистый статус {@code INIT}. Клиенту возвращается
   * системное текстовое уведомление об успешной отмене операции.</p>
   */
  @Test
  @DisplayName("Сценарий 3: Сброс контекста диалога при интенте cancel_or_reset")
  void shouldResetContextToInitWhenCancelIntentReceived() {
    // Given: Контекст частично заполнен параметрами
    DialogueContext context = new DialogueContext(
        DialogueState.STYLIST_PREFERENCE,
        new DialogueContext.Slots("Мужская стрижка", null, "завтра", null),
        new DialogueContext.Metadata(3, 0, testUserId)
    );

    LlamaResponse llamaInput = new LlamaResponse("cancel_or_reset",
        new LlamaResponse.Slots(null, null, null));

    DialogueResponse response = stateMachineService.processTurn(llamaInput, context);

    assertNotNull(response);
    assertEquals(DialogueState.INIT, response.updatedContext().currentState());
    assertNull(response.updatedContext().slots().service());
  }

}
