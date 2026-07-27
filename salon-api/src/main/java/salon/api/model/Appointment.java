package salon.api.model;

import io.avaje.validation.constraints.NotNull;
import io.avaje.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record Appointment(
    Long id,
    @NotNull Long clientId,
    @NotNull Long masterId,
    @NotNull LocalDateTime appointmentTime,// Заменяет if (appointmentTime == null)
    @Positive int durationMinutes,// Заменяет if (durationMinutes <= 0)
    @NotNull AppointmentStatus status,
    BigDecimal price,
    LocalDateTime createdAt
) {}
