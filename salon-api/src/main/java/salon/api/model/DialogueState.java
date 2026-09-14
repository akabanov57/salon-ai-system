package salon.api.model;

/**
 * <h3>Состояния конечного автомата диалога.</h3>
 */
public enum DialogueState {
  INIT,
  SERVICE_SELECTION,
  AVAILABILITY_MATCH,
  STYLIST_PREFERENCE,
  CONFIRMATION_PENDING,
  CLARIFY_INTENT
}
