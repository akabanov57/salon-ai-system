package salon.api.model;

import io.avaje.validation.constraints.NotNull;
import io.avaje.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record Appointment(
    Long id,
    @NotNull Long clientId,
    @NotNull Long masterId,
    @NotNull Long serviceId, // Добавили строгое поле услуги
    @NotNull LocalDateTime appointmentTime,// Заменяет if (appointmentTime == null)
    @Positive int durationMinutes, // Копируется из SERVICES на момент записи
    BigDecimal price, // Копируется из SERVICES на момент записи для финансового аудита
    @NotNull AppointmentStatus status,
    LocalDateTime createdAt
) {}
