package salon.api.model;

import io.avaje.jsonb.Json;
import io.avaje.validation.constraints.NotBlank;
import io.avaje.validation.constraints.NotNull;
import io.avaje.validation.constraints.Valid;

/**
 * <h2>DTO-модель результатов NLP-анализа текста от Llama3</h2>
 * <p>
 * Данный рекорд инкапсулирует структурированные данные, извлеченные нейросетью
 * из сырого текстового сообщения пользователя мессенджера.
 * </p>
 *
 * <p>Служит входным аргументом для бизнес-методов {@code SalonStateMachineService}.
 * Сериализация и валидация ограничений генерируются на этапе компиляции.</p>
 *
 * @param intent Намерение пользователя, классифицированное LLM
 *               (например: {@code "book_appointment"}, {@code "cancel_or_reset"}).
 * @param slots  Набор точечно извлеченных разговорных сущностей (слотов).
 */
@Valid    // Включает генерацию сompile-time адаптера валидации Avaje
@Json    // Включает генерацию compile-time JSON-парсера Avaje
public record LlamaResponse(

    @NotBlank(message = "Интент пользователя должен быть классифицирован")
    String intent,

    @NotNull(message = "Контейнер слотов не может быть null")
    Slots slots

) {
  /**
   * Внутренний контейнер сырых текстовых слотов, извлеченных из сообщения.
   * Поля могут принимать значения {@code null}, если они не были упомянуты клиентом.
   *
   * @param service      Распознанное наименование запрашиваемой процедуры.
   * @param stylist      Имя или псевдоним желаемого мастера салона.
   * @param datetimeRaw  Сырое упоминание даты/времени визита на естественном языке.
   */
  @Json
  public record Slots(
      String service,
      String stylist,
      String datetimeRaw
  ) {}

}
