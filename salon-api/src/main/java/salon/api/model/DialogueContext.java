package salon.api.model;

import io.avaje.jsonb.Json;

/**
 * <h3>Иммутабельный контекст сессии диалога.</h3>
 * <p>Инкапсулирует текущую точку графа конечного автомата, извлеченные сущности и метаданные.</p>
 */
public record DialogueContext(
    String currentState,
    Slots slots,
    Metadata metadata
) {

  public static DialogueContext createNew(String userId) {
    return new DialogueContext(
        "INIT",
        new Slots(null, null, null, null),
        new Metadata(0, 0, userId)
    );
  }

  public DialogueContext withState(String newState) {
    return new DialogueContext(newState, this.slots, this.metadata);
  }

  public DialogueContext withSlots(Slots newSlots) {
    return new DialogueContext(this.currentState, newSlots, this.metadata);
  }

  /**
   * Контейнер для группы извлеченных ИИ разговорных слотов.
   */
  @Json // Включает compile-time генерацию JSON-адаптера
  public record Slots(
      String service,
      String stylist,
      String datetimeRaw,
      String confirmedDatetime
  ) {

    /**
     * Принимает сырые слоты парсера Llama3 и безопасно сливает их
     * с текущим персистентным контекстом базы данных.
     *
     * @param source Извлеченные LLM-моделью слоты из LlamaResponse.
     * @return Новый иммутабельный объект Slots с обновленными текстовыми полями.
     */
    public Slots updateWith(LlamaResponse.Slots source) {
      return new Slots(
          source.service() != null ? source.service() : this.service,
          source.stylist() != null ? source.stylist() : this.stylist,
          source.datetimeRaw() != null ? source.datetimeRaw() : this.datetimeRaw,
          this.confirmedDatetime // Сохраняем старое валидированное время нетронутым
      );
    }
  }

  public record Metadata(
      int turnCount,
      int fallbackCount,
      String userId
  ) {

    public Metadata incrementTurn() {
      return new Metadata(this.turnCount + 1, this.fallbackCount, this.userId);
    }
  }
}
