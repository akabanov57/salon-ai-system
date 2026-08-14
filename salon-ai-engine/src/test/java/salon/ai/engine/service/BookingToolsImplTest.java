package salon.ai.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import io.avaje.inject.BeanScope;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import salon.ai.engine.internal.service.BookingTools;
import salon.api.model.Appointment;
import salon.api.model.AppointmentStatus;
import salon.api.model.CatalogService;
import salon.api.model.Master;
import salon.api.service.BookingService;

/**
 * <h2>Компонентный тест инструментов ИИ-ассистента (LangChain4j AI Tools Test Matrix)</h2>
 * <p>
 * Данный класс верифицирует работу низкоуровневых Java-инструментов, которые ЛЛМ (нейросеть)
 * использует как "интерфейсы действия" для связи с реальным миром СУБД. Тест проверяет корректность
 * маппинга доменных сущностей базы данных в текстовые строки (String), понятные языковой модели.
 * </p>
 *
 * <h4>Изоляция контекста:</h4>
 * <p>Для полной отвязки от физической сети и PostgreSQL, тест вручную инициализирует {@link BeanScope},
 * подставляя мок доменного интерфейса {@link BookingService}.</p>
 */
class BookingToolsImplTest {

  private static BeanScope beanScope;
  private static BookingService bookingServiceMock;
  private static BookingTools bookingTools;

  @BeforeAll
  static void setUpComponentContainer() {
    // 1. Вручную создаем изолированный Mock для внешней зависимости ровно один раз
    bookingServiceMock = Mockito.mock(BookingService.class);

    // 2. Инициализируем локальный контейнер Avaje BeanScope, принудительно внедряя туда наш Mock
    beanScope = BeanScope.builder()
        .beans(bookingServiceMock) // Добавляем заглушку как легитимный бин для @External
        .build();

    // 3. Извлекаем из собранного контейнера готовый ИИ-инструмент
    bookingTools = beanScope.get(BookingToolsImpl.class);
  }

  @AfterAll
  static void tearDownComponentContainer() {
    if (beanScope != null) {
      beanScope.close(); // Освобождаем ресурсы контейнера после прохождения всех тестов класса
    }
  }

  @BeforeEach
  void resetMockState() {
    // Мягко сбрасываем конфигурации вызовов мока перед каждым тестом, предотвращая взаимное влияние тестов
    Mockito.reset(bookingServiceMock);
  }

  /**
   * <h3>Тест 1: Успешное предварительное бронирование слота времени</h3>
   */
  @Test
  void shouldReturnSuccessStringWhenTimeSlotIsVacant() {
    // Arrange
    String platformId = "TG-123456";
    String masterAlias = "elena_colorist";
    String serviceName = "Женская стрижка модельная";
    String isoTimeStr = "2026-07-25T15:30";
    LocalDateTime parsedTime = LocalDateTime.parse(isoTimeStr);

    Appointment dummyApp = new Appointment(
        "SB-260725-ABCDE",
        platformId,
        masterAlias,
        serviceName,
        parsedTime,
        60,
        BigDecimal.valueOf(2500.00),
        AppointmentStatus.AI_PENDING,
        LocalDateTime.now()
    );

    Mockito.when(bookingServiceMock.tryAiBooking(platformId, masterAlias, serviceName, parsedTime))
        .thenReturn(Optional.of(dummyApp));

    // Act
    String result = bookingTools.bookAppointmentSlot(platformId, masterAlias, serviceName, isoTimeStr);

    // Assert
    String expected = "SUCCESS: Time slot reserved provisionally. Ticket Code: SB-260725-ABCDE. Status is currently AI_PENDING. " +
        "The client must await final confirmation from the salon owner.";
    assertEquals(expected, result);
  }

  /**
   * <h3>Тест 2: Отказ в бронировании при занятом слоте или конфликте расписания</h3>
   */
  @Test
  void shouldReturnFailureStringWhenTimeSlotIsOccupiedOrInvalid() {
    // Arrange
    Mockito.when(bookingServiceMock.tryAiBooking(anyString(), anyString(), anyString(), any(LocalDateTime.class)))
        .thenReturn(Optional.empty());

    // Act
    String result = bookingTools.bookAppointmentSlot("TG-123", "elena_colorist", "Невалидная услуга", "2026-07-25T15:30");

    // Assert
    String expected = "FAILURE: This time slot is already fully booked, clashes with an existing appointment, " +
        "lacks master qualification, or conflicts with the stylist's rest break. Please offer alternative slots.";
    assertEquals(expected, result);
  }

  /**
   * <h3>Тест 3: Перехват ошибок при некорректном ISO-формате даты от ИИ</h3>
   */
  @Test
  void shouldReturnErrorStringWhenDateTimeFormatIsMalformed() {
    // Act
    String result = bookingTools.bookAppointmentSlot("TG-123", "elena_colorist", "Стрижка", "Broken-Date-String");

    // Assert
    assertTrue(result.startsWith("ERROR: Invalid parameters passed or parsing failure occurred."));
  }

  /**
   * <h3>Тест 4: Успешный полнотекстовый поиск услуг в каталоге</h3>
   */
  @Test
  void shouldReturnFormattedStringWhenServicesMatchSearchKeyword() {
    // Arrange
    String keyword = "стрижка";
    CatalogService match = new CatalogService("Женская стрижка модельная", 60, BigDecimal.valueOf(2500.00));

    Mockito.when(bookingServiceMock.searchServicesInCatalog(keyword))
        .thenReturn(List.of(match));

    // Act
    String result = bookingTools.searchServices(keyword);

    // Assert
    String expected = "Official Service Name: 'Женская стрижка модельная' | Duration: 60 min | Price: 2500.00 rub";
    assertEquals(expected, result);
  }

  /**
   * <h3>Тест 5: Отсутствие совпадений при поиске по ключевому слову</h3>
   */
  @Test
  void shouldReturnFailureStringWhenNoServicesMatchKeyword() {
    // Arrange
    Mockito.when(bookingServiceMock.searchServicesInCatalog(anyString()))
        .thenReturn(Collections.emptyList());

    // Act
    String result = bookingTools.searchServices("массаж");

    // Assert
    String expected = "FAILURE: No services found matching 'массаж' in our menu. Please ask the client to clarify their request.";
    assertEquals(expected, result);
  }

  /**
   * <h3>Тест 6: Форматирование списка активных стилистов через бизнес-алиасы</h3>
   */
  @Test
  void shouldReturnFormattedRosterListingMasterAliases() {
    // Arrange
    Master activeMaster = new Master("elena_colorist", "Elena", "Petrova", "Top Colorist");
    Mockito.when(bookingServiceMock.getActiveMastersForDate(any(LocalDateTime.class)))
        .thenReturn(List.of(activeMaster));

    // Act
    String result = bookingTools.getAvailableStylists();

    // Assert
    String expected = "Alias: 'elena_colorist' | Name: Elena Petrova | Specialty: Top Colorist";
    assertEquals(expected, result);
  }

}
