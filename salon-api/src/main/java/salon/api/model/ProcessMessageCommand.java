package salon.api.model;

import io.avaje.validation.constraints.NotEmpty;
import io.avaje.validation.constraints.NotNull;

/**
 * <h2>Доменная команда: Запуск цикла обработки входящего обращения</h2>
 * <p>Чистый внутренний объект переноса данных между слоями приложения (Data Transfer Object).
 * Валидация полей выполняется декларативно с помощью фреймворка Avaje Validation.
 */
public record ProcessMessageCommand(
    @NotEmpty(message = "Trace ID обязателен для сквозного аудита операции.")
    String traceId,

    @NotNull(message = "Тип платформы (мессенджера) должен быть указан.")
    PlatformType platformType,

    @NotEmpty(message = "Идентификатор отправителя на платформе не может быть пустым.")
    String platformId,

    String firstName, // Имя может отсутствовать в некоторых мессенджерах (например, в Instagram)

    @NotNull(message = "Текст входящего сообщения не может быть null.")
    String messageText

) {}
