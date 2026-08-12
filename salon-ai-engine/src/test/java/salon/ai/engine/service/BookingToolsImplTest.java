package salon.ai.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import io.avaje.inject.BeanScope;
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

  // Статические поля для сохранения контекста между тестами
  private static final BookingService bookingServiceMock = Mockito.mock(BookingService.class);

  private static BookingTools bookingTools;

  @BeforeAll
  static void startPipeline() {
    beanScope = BeanScope.builder()
        .beans(bookingServiceMock)
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
    Mockito.reset(bookingServiceMock);
    bookingTools = beanScope.get(BookingToolsImpl.class);
  }

  /**
   * <h3>БИЗНЕС-КОНТЕКСТ: Получение сетки активных мастеров салона</h3>
   * <p><b>Сценарий:</b> В базе данных зарегистрированы доступные стилисты.</p>
   * <p><b>Ожидаемое поведение:</b> Инструмент вычищает технические рекорды jOOQ и преобразует
   * массив объектов в плоскую текстовую сетку, по которой ЛЛМ сможет сориентировать клиента.</p>
   */
  @Test
  void shouldFormatStylistsGridWhenMastersAreAvailable() {
    // Arrange - FIX: Removed the trailing 'isActive' boolean parameter from Master records
    Master master1 = new Master(1L, "Elena", "Petrova", "Top Colorist");
    Master master2 = new Master(2L, "Anna", "Ivanova", "Stylist");

    // FIX: Mock the new getActiveMastersForDate dynamic signature instead
    Mockito.when(bookingServiceMock.getActiveMastersForDate(any(LocalDateTime.class)))
        .thenReturn(List.of(master1, master2));

    // Act
    String result = bookingTools.getAvailableStylists();

    // Assert
    String expected = "ID: 1 | Name: Elena Petrova | Specialty: Top Colorist\n" +
        "ID: 2 | Name: Anna Ivanova | Specialty: Stylist";
    assertEquals(expected, result);
    verify(bookingServiceMock).getActiveMastersForDate(any(LocalDateTime.class));
  }

  /**
   * <h3>БИЗНЕС-КОНТЕКСТ: Получение сетки мастеров при пустом расписании</h3>
   * <p><b>Сценарий:</b> В базе данных нет ни одного активного мастера на выбранную дату.</p>
   * <p><b>Ожидаемое поведение:</b> Вместо пустой строки или падения, инструмент отдает вежливый
   * маркерный ответ, сообщающий ИИ, что на данный момент доступных специалистов нет.</p>
   */
  @Test
  void shouldReturnFriendlyMessageWhenNoStylistsExist() {
    // Arrange - FIX: Mock the new getActiveMastersForDate dynamic signature
    Mockito.when(bookingServiceMock.getActiveMastersForDate(any(LocalDateTime.class)))
        .thenReturn(Collections.emptyList());

    // Act
    String result = bookingTools.getAvailableStylists();

    // Assert
    assertEquals("Currently, there are no active stylists registered in the salon schedule system.", result);
  }

  /**
   * <h3>Test AI Tool: Successful execution path for vacant slot allocation</h3>
   * <p><b>Context:</b> The generative AI engine detects all parameters and triggers the tool.
   * The tool must safely parse the ISO-8601 string, forward the transaction request with the
   * extracted service ID, and map the domain response to a client-facing string template [Strict Grounding].</p>
   */
  @Test
  void shouldReturnSuccessStringWhenTimeSlotIsVacant() {
    // Arrange
    long clientId = 10L;
    long masterId = 1L;
    long serviceId = 55L; // Strict catalog service identifier
    String isoTimeStr = "2026-07-25T15:30";
    LocalDateTime parsedTime = LocalDateTime.parse(isoTimeStr);

    // Instantiate a valid domain model matching the new schema fields (including ServiceId and historical Price)
    Appointment dummyApp = new Appointment(
        42L,
        clientId,
        masterId,
        serviceId, // Injected service identifier anchor
        parsedTime,
        60, // Normal duration minutes extracted on server-side
        java.math.BigDecimal.valueOf(2500.00), // Historical audit price
        AppointmentStatus.AI_PENDING,
        LocalDateTime.now()
    );

    // Mock the updated service signature
    Mockito.when(bookingServiceMock.tryAiBooking(clientId, masterId, serviceId, parsedTime))
        .thenReturn(Optional.of(dummyApp));

    // Act: Invoke the modified tool method dropping duration parameters entirely
    String result = bookingTools.bookAppointmentSlot(clientId, masterId, serviceId, isoTimeStr);

    // Assert: Verify perfect string serialization output expected by the chat runtime loop
    String expected = "SUCCESS: Time slot reserved provisionally. Ticket ID: 42. Status is currently AI_PENDING. " +
        "The client must await final confirmation from the salon owner.";
    assertEquals(expected, result, "При успешном бронировании в БД инструмент обязан выдать строку подтверждения с номером тикета.");
  }

  /**
   * <h3>БИЗНЕС-КОНТЕКСТ: Занятое время или конфликт расписания</h3>
   * <p><b>Сценарий:</b> Клиент пытается записаться на время, которое уже занято другим гостем
   * либо пересекает окно отдыха/обеда мастера [Strict Grounding].</p>
   * <p><b>Ожидаемое поведение:</b> База отклоняет операцию (возвращает Optional.empty), а инструмент
   * сообщает нейросети строку "FAILURE", давая команду ИИ-ассистенту предложить клиенту другие свободные слоты [Strict Grounding].</p>
   */
  @Test
  void shouldReturnFailureStringWhenTimeSlotIsOccupied() {
    // Arrange: Mock the updated signature with three Long parameter matchers instead of anyInt()
    Mockito.when(bookingServiceMock.tryAiBooking(
            Mockito.anyLong(),
            Mockito.anyLong(),
            Mockito.anyLong(), // Matches the new serviceId parameter boundary
            Mockito.any(LocalDateTime.class)
        ))
        .thenReturn(Optional.empty());

    // Act: Invoke using the updated method signature (clientId, masterId, serviceId, dateTimeStr)
    String result = bookingTools.bookAppointmentSlot(10L, 1L, 55L, "2026-07-25T15:30");

    // Assert: Aligned with the exact updated text template defined inside BookingToolsImpl
    String expected = "FAILURE: This time slot is already fully booked, clashes with an existing appointment, " +
        "or conflicts with the stylist's rest break. Please offer alternative slots.";

    assertEquals(expected, result,
        "При накладке расписания инструмент обязан выдать инструкцию FAILURE для переориентации ЛЛМ.");
  }

  /**
   * <h3>БИЗНЕС-КОНТЕКСТ: Некорректный формат входящих данных от ИИ</h3>
   * <p><b>Сценарий:</b> Нейросеть ошиблась при разборе текста и передала некорректную строку даты
   * (например, "завтра") вместо строгого ISO-стандарта [Strict Grounding].</p>
   * <p><b>Ожидаемое поведение:</b> Метод перехватывает ошибку парсинга ISO-строки, предотвращая падение
   * всего потока выполнения, и возвращает маркер "ERROR" для исправления аргументов ИИ-модели [Strict Grounding].</p>
   */
  @Test
  void shouldReturnErrorStringWhenDateTimeFormatIsMalformed() {
    // Arrange
    long clientId = 10L;
    long masterId = 1L;
    long serviceId = 55L; // Передаем легитимный ID услуги для прохождения компиляции
    String malformedDate = "Broken-Date-String";

    // Act: Вызываем метод с новой сигнатурой (clientId, masterId, serviceId, dateTimeStr)
    String result = bookingTools.bookAppointmentSlot(clientId, masterId, serviceId, malformedDate);

    // Assert
    assertTrue(result.startsWith("ERROR: Invalid parameters passed or parsing failure occurred."),
        "При поврежденной строке даты инструмент обязан вернуть мягкую строку ошибки разбора.");
  }

}
