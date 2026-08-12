package salon.ai.engine.service;

import dev.langchain4j.agent.tool.Tool;
import io.avaje.inject.External;
import jakarta.inject.Singleton;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import salon.ai.engine.internal.service.BookingTools;
import salon.api.model.Master;
import salon.api.service.BookingService;

/**
 * <h2>Инструменты ИИ-ассистента для работы с СУБД (LangChain4j Tools)</h2>
 * <p>
 * Реализует интерфейс {@code BookingTools}. Обеспечивает динамический
 * доступ нейросетевого ядра к расписанию мастеров салона красоты.
 * </p>
 */
@Singleton
final class BookingToolsImpl implements BookingTools {

  private static final Logger log = LoggerFactory.getLogger(BookingToolsImpl.class);

  private final BookingService bookingService;

  BookingToolsImpl(@External BookingService bookingService) {
    this.bookingService = bookingService;
  }

  @Tool("Retrieves a complete list of all active hair stylists, masters, and colorists along with their specialties.")
  @Override
  public String getAvailableStylists() {
    log.info("AI Tool: Intercepted request to fetch active stylists grid for today.");

    final LocalDateTime today = LocalDateTime.now();
    final List<Master> masters = bookingService.getActiveMastersForDate(today);

    if (masters.isEmpty()) {
      return "Currently, there are no active stylists registered in the salon schedule system.";
    }

    // ЭТАЛОННЫЙ ДЕКЛАРАТИВНЫЙ СТРИМ-ВАРИАНТ С ИСПРАВЛЕННЫМ SPECIALIZATION()
    return masters.stream()
        .map(m -> String.format("ID: %d | Name: %s %s | Specialty: %s",
            m.id(), m.firstName(), m.lastName(), m.specialization()))
        .collect(Collectors.joining("\n"));
  }

  @Tool("Attempts to provisionally book a specific time slot for a client with a selected master for a specific service. Time format must be ISO local format: YYYY-MM-DDTHH:MM.")
  @Override
  public String bookAppointmentSlot(long clientId, long masterId, long serviceId, String dateTimeStr) {
    // FIX: Using precise SLF4J brace placeholder parameters instead of old syntax tokens
    log.info(
        "AI Tool Invocation: Attempting slot reservation for master [{}] and service [{}] at [{}]",
        masterId, serviceId, dateTimeStr);

    try {
      final LocalDateTime time = LocalDateTime.parse(dateTimeStr);

      // FIX: Clean functional mapping routed completely via the updated serviceId signature contract
      return bookingService.tryAiBooking(clientId, masterId, serviceId, time)
          .map(app -> String.format(
              "SUCCESS: Time slot reserved provisionally. Ticket ID: %d. Status is currently %s. The client must await final confirmation from the salon owner.",
              app.id(), app.status()
          ))
          .orElse(
              "FAILURE: This time slot is already fully booked, clashes with an existing appointment, or conflicts with the stylist's rest break. Please offer alternative slots.");

    } catch (Exception e) {
      log.error("AI Tool Anomaly: Failed to evaluate allocation step for master [{}]. Reason: {}",
          masterId, e.getMessage(), e);
      return "ERROR: Invalid parameters passed or parsing failure occurred. Verify date string format conforms strictly to ISO local standards.";
    }
  }
}
