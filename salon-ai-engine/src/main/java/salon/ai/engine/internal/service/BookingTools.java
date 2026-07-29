package salon.ai.engine.internal.service;

/**
 * <h2>DESIGN INTENT: AI Function-Calling Contract Port</h2>
 * This interface acts as the strict infrastructure-isolated boundary that declares what structural
 * actions and operations the LLM can execute inside the salon system ecosystem.
 *
 * <p><b>WHY IT EXISTS:</b>
 * Instead of giving the LLM raw database connections or direct access to use-cases, we provide this
 * controlled API layer. LangChain4j parses the structural signatures of these methods to generate
 * runtime JSON schema definitions, which are sent to the AI model as pluggable capabilities.</p>
 *
 * <p><b>ARCHITECTURAL ROLE:</b>
 * This component acts as a high-utility translation boundary. It takes text-based parameters from the AI,
 * passes them into type-safe business services, and returns a clean string explanation back to the AI
 * to guide the next conversational turn.</p>
 */
public interface BookingTools {

  /**
   * Fetches the current, active roster of hair stylists, beauty masters, and colorists.
   * Used by the AI when a customer asks who works at the salon or wants to see available professionals.
   *
   * @return A formatted text string listing active masters, their unique system database IDs,
   *         and professional specializations. Returns a helpful error message if no professionals are found.
   */
  String getAvailableStylists();

  /**
   * Executes a transactional appointment slot reservation within the core domain schedule matrix.
   * Used by the AI after it successfully collects all mandatory parameters from the chat interaction.
   *
   * @param clientId      The unique database identifier tracking the verified client profile.
   * @param masterId      The unique database identifier tracking the selected stylist.
   * @param dateTimeStr   The target date and time string formatted strictly in ISO-8601 local standard
   *                      notation (e.g., {@code 2026-07-29T14:00}).
   * @param durationMinutes The calculated execution allocation window for the requested service.
   * @return A descriptive text string detailing the reservation outcome (e.g., successful creation
   *         with a ticket tracking ID, or a failure notice if a time conflict occurs) which the AI
   *         will present back to the user.
   */
  String bookAppointmentSlot(long clientId, long masterId, String dateTimeStr, int durationMinutes);
}
