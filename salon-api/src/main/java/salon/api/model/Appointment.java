package salon.api.model;

import io.avaje.validation.constraints.NotBlank;
import io.avaje.validation.constraints.NotNull;
import io.avaje.validation.constraints.Positive;
import io.avaje.validation.constraints.PositiveOrZero;
import io.avaje.validation.constraints.Valid;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <h3>Доменное представление сеанса записи визита клиента</h3>
 *
 * <p>Сущность, агрегирующая параметры бронирования слота времени.
 * Полностью очищена от суррогатных числовых идентификаторов базы данных.
 * Вместо технического автоинкремента СУБД использует уникальный строковый
 * бизнес-код билета визита (ticketCode) для сквозной идентификации.</p>
 */
@Valid
public record Appointment(
    @NotBlank(message = "Публичный бизнес-код записи визита (ticketCode) не может быть пустым.")
    String ticketCode,   // Публичный бизнес-код записи визита (например, 'SB-20260810-A7X')

    @NotBlank(message = "Естественный ключ клиента (platformId) не может быть пустым.")
    String platformId,   // Естественный ключ клиента (например, chat_id из Telegram)

    @NotBlank(message = "Естественный ключ мастера (masterAlias) не может быть пустым.")
    String masterAlias,  // Естественный ключ мастера (псевдоним, например 'elena_colorist')

    @NotBlank(message = "Наименование запрашиваемой услуги (serviceName) не может быть пустым.")
    String serviceName,  // Естественный ключ услуги (наименование из каталога)

    @NotNull(message = "Целевое время начала сеанса визита не может быть null.")
    LocalDateTime appointmentTime,

    @Positive(message = "Продолжительность процедуры в минутах должна быть положительным числом.")
    int durationMinutes, // Копируется из настроек услуги на момент фиксации сделки

    @NotNull(message = "Финансовая стоимость услуги на момент бронирования не может быть null.")
    @PositiveOrZero(message = "Историческая цена визита не может быть отрицательной.")
    BigDecimal price,    // Фиксируется исторически на момент совершения ИИ-бронирования

    @NotNull(message = "Текущий статус жизненного цикла записи не может быть null.")
    AppointmentStatus status,

    @NotNull(message = "Время создания записи на стороне сервера не может быть null.")
    LocalDateTime createdAt
) {}
