package salon.ai.engine.internal.service;

/**
 * Internal interface contract defining the function-calling tools
 * made available exclusively to the LangChain4j LLM engine.
 */
public interface BookingTools {

//  @Tool("Retrieves a complete list of all active hair stylists, masters, and colorists along with their specialties.")
  String getAvailableStylists();

//  @Tool("Attempts to provisionally book a specific time slot for a client with a selected master. Time format must be ISO local format: YYYY-MM-DDTHH:MM.")
  String bookAppointmentSlot(long clientId, long masterId, String dateTimeStr, int durationMinutes);
}
