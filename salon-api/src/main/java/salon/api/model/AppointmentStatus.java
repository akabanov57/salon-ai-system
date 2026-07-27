package salon.api.model;

public enum AppointmentStatus {
  /** The AI booked the slot during a chat session, awaiting your wife's final approval */
  AI_PENDING,

  /** Confirmed booking, visible in the green Vaadin calendar grid */
  APPROVED,

  /** Canceled either by the client through the bot or manually by the salon */
  CANCELED,

  /** Visited successfully, ready for automated AI feedback follow-ups */
  COMPLETED
}
