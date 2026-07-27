package salon.ai.engine.service;

import dev.langchain4j.agent.tool.Tool;
import jakarta.inject.Singleton;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import salon.ai.engine.internal.service.BookingTools;
import salon.api.model.Master;
import salon.api.service.BookingService;

@Singleton
final class BookingToolsImpl implements BookingTools {

  private static final Logger log = LoggerFactory.getLogger(BookingToolsImpl.class);

  private final BookingService bookingService;

  public BookingToolsImpl(BookingService bookingService) {
    this.bookingService = bookingService;
  }

  @Tool("Retrieves a complete list of all active hair stylists, masters, and colorists along with their specialties.")
  @Override
  public String getAvailableStylists() {
    log.info("AI Tool Invocation: Fetching active stylists grid");
    final List<Master> masters = bookingService.getAvailableStylists();
    if (masters.isEmpty()) {
      return "Currently, there are no active stylists registered in the salon schedule system.";
    }
    return masters.stream()
        .map(m -> String.format("ID: %d | Name: %s %s | Specialty: %s", m.id(), m.firstName(), m.lastName(), m.specialization()))
        .collect(Collectors.joining("\n"));
  }

  @Tool("Attempts to provisionally book a specific time slot for a client with a selected master. Time format must be ISO local format: YYYY-MM-DDTHH:MM.")
  @Override
  public String bookAppointmentSlot(long clientId, long masterId, String dateTimeStr,
      int durationMinutes) {
    log.info("AI Tool Invocation: Attempting slot reservation for master {} at {}", masterId, dateTimeStr);
    try {
      final LocalDateTime time = LocalDateTime.parse(dateTimeStr);

      // ИСПРАВЛЕНО: Чистый функциональный стиль без ручных if-проверок и без .get()/.orElseThrow()
      return bookingService.tryAiBooking(clientId, masterId, time, durationMinutes)
          .map(app -> String.format(
              "SUCCESS: Time slot reserved provisionally. Ticket ID: %d. Status is currently %s. The client must await final confirmation from the salon owner.",
              app.id(), app.status()
          ))
          .orElse("FAILURE: This time slot is already fully booked or clashes with an existing appointment. Please offer alternative slots.");

    } catch (Exception e) {
      log.error("AI Tool Anomaly: Failed to evaluate allocation step. Reason: {}", e.getMessage());
      return "ERROR: Invalid parameters passed or parsing failure occurred. Verify date string format conforms strictly to ISO local standards.";
    }
  }
}
