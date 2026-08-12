package salon.api.model;

/**
 * <h3>Конечный автомат статусов жизненного цикла записи (Сценарий 4)</h3>
 */
public enum AppointmentStatus {
  /** The AI booked the slot during a chat session, awaiting your wife's final approval */
  AI_PENDING,

  /** Confirmed booking, visible in the green Vaadin calendar grid */
  APPROVED,

  /** Canceled either by the client through the bot or manually by the salon */
  CANCELED,

  /** Visited successfully, ready for automated AI feedback follow-ups */
  COMPLETED;

  /**
   * Проверяет, разрешен ли математический переход из текущего статуса в целевой.
   *
   * @param target Целевой статус, в который переводится запись
   * @return true, если переход легитимен согласно правилам Сценария 4
   */
  public boolean canTransitionTo(AppointmentStatus target) {
    return switch (this) {
      case AI_PENDING -> target == APPROVED || target == CANCELED;
      case APPROVED   -> target == COMPLETED || target == CANCELED;
      case CANCELED, COMPLETED -> false; // Терминальные статусы. Движение из них невозможно
    };
  }
}
