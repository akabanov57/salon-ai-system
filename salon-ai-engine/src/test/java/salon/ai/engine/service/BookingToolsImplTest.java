package salon.ai.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import salon.ai.engine.internal.service.BookingTools;
import salon.api.model.Appointment;
import salon.api.model.AppointmentStatus;
import salon.api.model.Master;
import salon.api.service.BookingService;

class BookingToolsImplTest {

  // Статические поля для сохранения контекста между тестами
  private static BookingService bookingServiceMock;

  private static BookingTools bookingTools;

  @BeforeAll
  static void beforeAll() {
    // Инициализируем мок и тестируемый объект ровно один раз
    bookingServiceMock = Mockito.mock(BookingService.class);
    bookingTools = new BookingToolsImpl(bookingServiceMock);
  }

  @AfterEach
  void tearDown() {
    // ПАТТЕРН ИЗ AI-DEMO: Сбрасываем стаббинги и счетчики вызовов мока
    Mockito.reset(bookingServiceMock);
  }

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
    assertEquals(expected, result);
    verify(bookingServiceMock).getAvailableStylists();
  }

  @Test
  void shouldReturnFriendlyMessageWhenNoStylistsExist() {
    // Arrange
    Mockito.when(bookingServiceMock.getAvailableStylists()).thenReturn(Collections.emptyList());

    // Act
    String result = bookingTools.getAvailableStylists();

    // Assert
    assertEquals("Currently, there are no active stylists registered in the salon schedule system.", result);
  }

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
    assertEquals(expected, result);
  }

  @Test
  void shouldReturnFailureStringWhenTimeSlotIsOccupied() {
    // Arrange
    Mockito.when(bookingServiceMock.tryAiBooking(anyLong(), anyLong(), any(LocalDateTime.class), anyInt()))
        .thenReturn(Optional.empty());

    // Act
    String result = bookingTools.bookAppointmentSlot(10L, 1L, "2026-07-25T15:30", 60);

    // Assert
    assertEquals("FAILURE: This time slot is already fully booked or clashes with an existing appointment. Please offer alternative slots.", result);
  }

  @Test
  void shouldReturnErrorStringWhenDateTimeFormatIsMalformed() {
    // Act
    // Pass a broken date string that fails LocalDateTime.parse() execution blocks
    String result = bookingTools.bookAppointmentSlot(10L, 1L, "Broken-Date-String", 60);

    // Assert
    assertTrue(result.startsWith("ERROR: Invalid parameters passed or parsing failure occurred."));
  }

}
