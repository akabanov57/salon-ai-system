package salon.db.jooq.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static salon.db.jooq.generated.Tables.APPOINTMENTS;

import io.avaje.inject.test.InjectTest;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import java.util.Optional;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import salon.api.exception.IntegrityViolationException;
import salon.api.model.Appointment;
import salon.api.model.Client;
import salon.api.service.BookingService;

@InjectTest // CORRECT: Instructs avaje-inject to spin up the test container context
public class BookingServiceImplTest {

  @Inject
  public BookingService bookingService;

  @Inject
  public DSLContext ctx;

  @BeforeEach
  void setUp() {
    // 1. Временно выключаем проверку внешних ключей в H2, чтобы разрешить очистку
    ctx.execute("SET REFERENTIAL_INTEGRITY FALSE");

    // 2. Очищаем таблицы и сбрасываем счетчики ID (RESTART IDENTITY поддерживается в H2)
    ctx.execute("TRUNCATE TABLE appointments RESTART IDENTITY");
    ctx.execute("TRUNCATE TABLE clients RESTART IDENTITY");
    ctx.execute("TRUNCATE TABLE masters RESTART IDENTITY");

    // 3. Обязательно включаем проверку ссылочной целостности обратно!
    ctx.execute("SET REFERENTIAL_INTEGRITY TRUE");

    // 4. Накатываем фикстуру (мастера) для текущего теста
    ctx.execute("INSERT INTO masters (first_name, last_name, specialization, is_active) " +
        "VALUES ('Elena', 'Petrova', 'Top Colorist', true)");
  }

  @AfterEach
  void tearDown() {
    MDC.clear();
  }

  @Test
  void shouldRegisterNewTelegramClientOnFirstContact() {
    MDC.put("traceId", "TX-ON-FIRST-CONTACT-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    // Act
    Client client = bookingService.identifyOrCreateTelegramClient("55512345", "Natalia");

    // Assert
    assertNotNull(client.id(), "A database surrogate ID should be auto-assigned.");
    assertEquals("Natalia", client.firstName());
    assertEquals("55512345", client.telegramId());
  }

  @Test
  void shouldRetrieveExistingClientWithoutDuplicates() {
    MDC.put("traceId", "TX-ON-CLIENT-WITHOUT-DUPLICATES-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    // Arrange
    Client firstPass = bookingService.identifyOrCreateTelegramClient("99999", "Olga");

    MDC.put("traceId", "TX-ON-CLIENT-WITHOUT-DUPLICATES-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    // Act
    Client secondPass = bookingService.identifyOrCreateTelegramClient("99999", "Olga (Updated Handle)");

    // Assert
    assertEquals(firstPass.id(), secondPass.id(), "Should resolve to the identical record ID match.");
    assertEquals("Olga", secondPass.firstName(), "The stored state should maintain structural data.");
  }

  @Test
  void shouldSuccessfullyBookAvailableTimeSlot() {
    MDC.put("traceId", "TX-AVAILABLE-TIME-SLOT-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    // Arrange
    Client client = bookingService.identifyOrCreateTelegramClient("11111", "Anna");
    Long masterId = 1L; // Elena Petrova auto identity serial from seed
    LocalDateTime bookingTime = LocalDateTime.of(2026, 7, 20, 14, 0);

    MDC.put("traceId", "TX-AVAILABLE-TIME-SLOT-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    // Act
    Optional<Appointment> appointmentOpt = bookingService.tryAiBooking(client.id(), masterId, bookingTime, 60);

    // Assert
    assertTrue(appointmentOpt.isPresent(), "Appointment should save successfully when slot is vacant.");
    Appointment appointment = appointmentOpt.get();
    assertNotNull(appointment.id());
    assertEquals(client.id(), appointment.clientId());
    assertEquals(masterId, appointment.masterId());
  }

  @Test
  void shouldRejectConcurrentBookingClashesViaOverlapLogic() {
    // Arrange
    MDC.put("traceId", "TX-CLASHES-VIA-OVERLAP-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    Client clientA = bookingService.identifyOrCreateTelegramClient("11111", "Anna");
    MDC.put("traceId", "TX-CLASHES-VIA-OVERLAP-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    Client clientB = bookingService.identifyOrCreateTelegramClient("22222", "Svetlana");
    Long masterId = 1L;
    LocalDateTime bookingTime = LocalDateTime.of(2026, 7, 20, 14, 0);

    // Act - Lock the slot down for Anna
    MDC.put("traceId", "TX-CLASHES-VIA-OVERLAP-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    Optional<Appointment> bookingA = bookingService.tryAiBooking(clientA.id(), masterId, bookingTime, 60);
    assertTrue(bookingA.isPresent());

    // Attempt overlapping reservation for Svetlana inside that exact window (e.g., 14:30)
    MDC.put("traceId", "TX-CLASHES-VIA-OVERLAP-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    Optional<Appointment> bookingB = bookingService.tryAiBooking(clientB.id(), masterId, bookingTime.plusMinutes(30), 60);

    // Assert
    assertFalse(bookingB.isPresent(), "The service overlap layer verification block must block slot overlap collisions.");
  }

  @Test
  void shouldTranslateDatabaseUniqueConstraintsIntoCleanDomainExceptions() {
    // Arrange
    // 1. Register a valid client first so we have a real ID in the database
    MDC.put("traceId", "TX-CLEAN-DOMAIN-EXCEPTIONS-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    Client client = bookingService.identifyOrCreateTelegramClient("777", "Natalia");

    Long masterId = 1L; // Master Elena seeded automatically in setUp()
    LocalDateTime bookingTime = LocalDateTime.of(2026, 7, 20, 14, 0);

    // 2. Insert the canceled appointment row directly via jOOQ.
    // It uses the valid client.id(), satisfying the Foreign Key constraint.
    MDC.put("traceId", "TX-CLEAN-DOMAIN-EXCEPTIONS-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    ctx.insertInto(APPOINTMENTS)
        .set(APPOINTMENTS.CLIENT_ID, client.id())
        .set(APPOINTMENTS.MASTER_ID, masterId)
        .set(APPOINTMENTS.APPOINTMENT_TIME, bookingTime)
        .set(APPOINTMENTS.DURATION_MINUTES, 60)
        .set(APPOINTMENTS.STATUS, "CANCELED")
        .execute();

    // Act & Assert
    // 3. Try to book the exact same slot.
    // The Java code thinks 'CANCELED' means vacant and triggers the INSERT.
    // The database catches the duplicate (masterId + bookingTime) index, throwing the integrity error.
    MDC.put("traceId", "TX-CLEAN-DOMAIN-EXCEPTIONS-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    assertThrows(IntegrityViolationException.class, () -> bookingService.tryAiBooking(client.id(), masterId, bookingTime, 60), "The service layer wrapper must trap internal jOOQ unique constraint anomalies and rethrow pure Domain Exception types.");
  }

}
