package salon.ai.engine.service;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.service.MemoryId;
import io.avaje.inject.External;
import jakarta.inject.Singleton;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import salon.ai.engine.internal.service.BookingTools;
import salon.api.model.CatalogService;
import salon.api.model.Master;
import salon.api.service.BookingService;

/**
 * <h2>Инструменты ИИ-ассистента для работы с СУБД (LangChain4j Tools Implementation)</h2>
 * <p>
 * Реализует интерфейс {@code BookingTools}. Выступает в роли "рук" большой языковой модели,
 * транслируя неструктурированные текстовые намерения в безопасные вызовы доменных сервисов.
 * </p>
 */
@Singleton
final class BookingToolsImpl implements BookingTools {

  private static final Logger log = LoggerFactory.getLogger(BookingToolsImpl.class);

  private final BookingService bookingService;

  /**
   * Конструктор инжектирует только доменный интерфейс BookingService.
   * Модуль 'salon-ai-engine' остается кристально чистым: он не зависит от jOOQ, SQL или драйверов JDBC.
   */
  BookingToolsImpl(@External BookingService bookingService) {
    this.bookingService = bookingService;
  }

  @Tool("Retrieves a complete list of all active hair stylists, beauty masters, and colorists along with their specialties and unique conversational ALIAS strings.")
  @Override
  public String getAvailableStylists() {
    log.info("AI Tool: Intercepted request to fetch active stylists grid for today.");

    final LocalDateTime today = LocalDateTime.now();
    final List<Master> masters = bookingService.getActiveMastersForDate(today);

    if (masters.isEmpty()) {
      return "Currently, there are no active stylists registered in the salon schedule system.";
    }

    // Стримим мастеров, выводя их уникальные текстовые псевдонимы (ALIAS) вместо системных ID
    return masters.stream()
        .map(m -> String.format("Alias: '%s' | Name: %s %s | Specialty: %s",
            m.alias(), m.firstName(), m.lastName(), m.specialization()))
        .collect(Collectors.joining("\n"));
  }

  @Tool("Attempts to provisionally book a specific time slot for a client with a selected master for a specific service. Time format must be ISO local format: YYYY-MM-DDTHH:MM.")
  @Override
  public String bookAppointmentSlot(
      @P("The unique social account/messenger identifier of the active client (e.g., '12345678').") @MemoryId String platformId,
      @P("The unique conversational alias string of the selected stylist (e.g., 'elena_colorist').") String masterAlias,
      @P("The exact, official name of the requested procedure from the menu (e.g., 'Женская стрижка модельная').") String serviceName,
      @P("The target appointment start date and time formatted strictly in ISO-8601 standard (e.g., '2026-08-25T14:30').") String dateTimeStr) {

    log.info("AI Tool Invocation: Attempting slot reservation using business keys for Client [{}], Master [{}] and Service [{}] at [{}]",
        platformId, masterAlias, serviceName, dateTimeStr);

    try {
      final LocalDateTime time = LocalDateTime.parse(dateTimeStr);

      // Вызываем очищенный доменный сервис, возвращающий ticketCode вместо суррогатного Long ID
      return bookingService.tryAiBooking(platformId, masterAlias, serviceName, time)
          .map(app -> String.format(
              "SUCCESS: Time slot reserved provisionally. Ticket Code: %s. Status is currently %s. The client must await final confirmation from the salon owner.",
              app.ticketCode(), app.status()
          ))
          .orElse("FAILURE: This time slot is already fully booked, clashes with an existing appointment, lacks master qualification, or conflicts with the stylist's rest break. Please offer alternative slots.");

    } catch (Exception e) {
      log.error("AI Tool Anomaly: Failed to evaluate allocation step for master [{}]. Reason: {}",
          masterAlias, e.getMessage(), e);
      return "ERROR: Invalid parameters passed or parsing failure occurred. Verify date string format conforms strictly to ISO local standards.";
    }
  }

  @Tool("Searches the official salon catalog for active services matching a descriptive keyword (e.g., 'haircut', 'coloring') to discover their exact official names.")
  @Override
  public String searchServices(
      @P("The descriptive keyword to find matching services in the catalogue (e.g., 'стрижка').") String searchKeyword) {
    log.info("AI Tool: Processing service catalog query for keyword: [{}]", searchKeyword);

    try {
      // Чистый делегирующий вызов в доменный сервис без прямого SQL/jOOQ кода
      List<CatalogService> foundServices = bookingService.searchServicesInCatalog(searchKeyword);

      if (foundServices.isEmpty()) {
        log.warn("AI Tool: Service lookup returned 0 results for keyword [{}]", searchKeyword);
        return String.format("FAILURE: No services found matching '%s' in our menu. Please ask the client to clarify their request.", searchKeyword);
      }

      // Собираем результаты в понятную для контекстного окна LLM текстовую матрицу
      // FIX: Swapped raw %s string conversion for explicit %.2f precision monetary token serialization
      return foundServices.stream()
          .map(s -> String.format("Official Service Name: '%s' | Duration: %d min | Price: %.2f rub",
              s.name(), s.durationMinutes(), s.price()))
          .collect(Collectors.joining("\n"));

    } catch (Exception e) {
      log.error("AI Tool Anomaly: Exception caught during service search layout: {}", e.getMessage(), e);
      return "ERROR: Unable to read salon service specifications. Please prompt the user to try again later.";
    }
  }
}
