package salon.ai.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
    // Arrange
    Master master1 = new Master(1L, "Elena", "Petrova", "Top Colorist", true);
    Master master2 = new Master(2L, "Anna", "Ivanova", "Stylist", true);
    Mockito.when(bookingServiceMock.getAvailableStylists()).thenReturn(List.of(master1, master2));

    // Act
    String result = bookingTools.getAvailableStylists();

    // Assert
    String expected = "ID: 1 | Name: Elena Petrova | Specialty: Top Colorist\n" +
        "ID: 2 | Name: Anna Ivanova | Specialty: Stylist";
    assertEquals(expected, result, "Сетка мастеров должна быть отформатирована в строгий текстовый реестр.");
    verify(bookingServiceMock).getAvailableStylists();
  }

  /**
   * <h3>БИЗНЕС-КОНТЕКСТ: Получение сетки мастеров при пустом расписании</h3>
   * <p><b>Сценарий:</b> В базе данных нет ни одного активного мастера на выбранную дату.</p>
   * <p><b>Ожидаемое поведение:</b> Вместо пустой строки или падения, инструмент отдает вежливый
   * маркерный ответ, сообщающий ИИ, что на данный момент доступных специалистов нет.</p>
   */
  @Test
  void shouldReturnFriendlyMessageWhenNoStylistsExist() {
    // Arrange
    Mockito.when(bookingServiceMock.getAvailableStylists()).thenReturn(Collections.emptyList());

    // Act
    String result = bookingTools.getAvailableStylists();

    // Assert
    assertEquals("Currently, there are no active stylists registered in the salon schedule system.", result,
        "При пустом списке СУБД инструмент обязан вернуть понятный текстовый маркер отказа.");
  }

  /**
   * <h3>БИЗНЕС-КОНТЕКСТ: Резервирование свободного слота времени</h3>
   * <p><b>Сценарий:</b> Выбранное клиентом время полностью свободно в шахматке расписания.</p>
   * <p><b>Ожидаемое поведение:</b> СУБД создает запись брони в статусе пред-проверки (AI_PENDING),
   * а инструмент генерирует строку "SUCCESS" с номером билета для отправки клиенту.</p>
   */
  @Test
  void shouldReturnSuccessStringWhenTimeSlotIsVacant() {
    // Arrange
    long clientId = 10L;
    long masterId = 1L;
    String isoTimeStr = "2026-07-25T15:30";
    LocalDateTime parsedTime = LocalDateTime.parse(isoTimeStr);

    Appointment dummyApp = new Appointment(
        42L, clientId, masterId, parsedTime, 60, AppointmentStatus.AI_PENDING, null, null
    );

    Mockito.when(bookingServiceMock.tryAiBooking(clientId, masterId, parsedTime, 60))
        .thenReturn(Optional.of(dummyApp));

    // Act
    String result = bookingTools.bookAppointmentSlot(clientId, masterId, isoTimeStr, 60);

    // Assert
    String expected = "SUCCESS: Time slot reserved provisionally. Ticket ID: 42. Status is currently AI_PENDING. " +
        "The client must await final confirmation from the salon owner.";
    assertEquals(expected, result, "При успешном бронировании в БД инструмент обязан выдать строку подтверждения с номером тикета.");
  }

  /**
   * <h3>БИЗНЕС-КОНТЕКСТ: Занятое время или конфликт расписания</h3>
   * <p><b>Сценарий:</b> Клиент пытается записаться на время, которое уже занято другим гостем.</p>
   * <p><b>Ожидаемое поведение:</b> База отклоняет операцию (возвращает Optional.empty), а инструмент
   * сообщает нейросети строку "FAILURE", давая команду ИИ-ассистенту предложить клиенту другие свободные слоты.</p>
   */
  @Test
  void shouldReturnFailureStringWhenTimeSlotIsOccupied() {
    // Arrange
    Mockito.when(bookingServiceMock.tryAiBooking(anyLong(), anyLong(), any(LocalDateTime.class), anyInt()))
        .thenReturn(Optional.empty());

    // Act
    String result = bookingTools.bookAppointmentSlot(10L, 1L, "2026-07-25T15:30", 60);

    // Assert
    assertEquals("FAILURE: This time slot is already fully booked or clashes with an existing appointment. Please offer alternative slots.", result,
        "При накладке расписания инструмент обязан выдать инструкцию FAILURE для переориентации ЛЛМ.");
  }

  /**
   * <h3>БИЗНЕС-КОНТЕКСТ: Некорректный формат входящих данных от ИИ</h3>
   * <p><b>Сценарий:</b> Нейросеть ошиблась при разборе текста и передала некорректную строку даты (например, "завтра").</p>
   * <p><b>Ожидаемое поведение:</b> Метод перехватывает ошибку парсинга ISO-строки, предотвращая падение
   * всего потока выполнения, и возвращает маркер "ERROR" для исправления аргументов ИИ.</p>
   */
  @Test
  void shouldReturnErrorStringWhenDateTimeFormatIsMalformed() {
    // Act
    String result = bookingTools.bookAppointmentSlot(10L, 1L, "Broken-Date-String", 60);

    // Assert
    assertTrue(result.startsWith("ERROR: Invalid parameters passed or parsing failure occurred."),
        "При поврежденной строке даты инструмент обязан вернуть мягкую строку ошибки разбора.");
  }

}
