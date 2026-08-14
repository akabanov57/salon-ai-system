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
 *
 * <h2>DESIGN INTENT: AI Function-Calling Contract Port</h2>
 * Этот интерфейс определяет изолированные границы доступных действий, которые ИИ-модель
 * может выполнять внутри инфраструктуры салона красоты (LangChain4j Tools Contract).
 *
 * <p><b>АРХИТЕКТУРНАЯ РОЛЬ:</b>
 * Компонент полностью переведен на использование естественных бизнес-ключей (Natural/Business Keys).
 * Нейросеть больше не оперирует сырыми числовыми ID базы данных, что предотвращает утечку абстракций,
 * снижает галлюцинации LLM и защищает СУБД от некорректных параметров.</p>
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
   * Осуществляет транзакционное предварительное бронирование слота времени на основе строковых бизнес-ключей.
   */
  String bookAppointmentSlot(String platformId, String masterAlias, String serviceName, String dateTimeStr);

  /**
   * Searches the official salon catalog for active services matching a textual keyword or description.
   * Used by the AI to look up precise system database service IDs before initiating a booking.
   *
   * @param searchKeyword A textual query representing the requested procedure (e.g., "haircut", "coloring").
   * @return A formatted text string listing matching services, their unique system database IDs,
   *         normative execution durations, and prices. Returns an instructive error if no services match.
   */
  String searchServices(String searchKeyword);
}
