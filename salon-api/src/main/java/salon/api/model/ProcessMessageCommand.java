package salon.api.model;

import io.avaje.validation.constraints.NotBlank;
import io.avaje.validation.constraints.NotNull;

/**
 * <h2>Доменная команда: Запуск цикла обработки входящего обращения</h2>
 * <p>Чистый внутренний объект переноса данных между слоями приложения (Data Transfer Object).
 * Валидация полей выполняется декларативно с помощью фреймворка Avaje Validation.
 */
public record ProcessMessageCommand(
    @NotBlank(message = "Trace ID обязателен для сквозного аудита операции.")
    String traceId,

    @NotNull(message = "Тип платформы (мессенджера) должен быть указан.")
    PlatformType platformType,

    @NotBlank(message = "Идентификатор отправителя на платформе не может быть пустым.")
    String platformId,

    /**
     * Идентификатор сообщения в мессенджере (для telegram это значение update_id).
     * Необходим для обеспечения идемпотентности обработки.
     */
    @NotBlank(message = "Идентификатор сообщения мессенджера обязателен для проверки идемпотентности.")
    String messengerMessageId,

    String displayName, // Может быть null, мы нормализуем его дефолтом "Guest" на уровне сервиса СУБД

    @NotNull(message = "Текст входящего сообщения не может быть null.")
    String messageText

) {}
