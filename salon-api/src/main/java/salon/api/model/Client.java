package salon.api.model;

import io.avaje.validation.constraints.NotBlank;
import io.avaje.validation.constraints.PositiveOrZero;
import java.time.LocalDateTime;

public record Client(
    Long id,
    @NotBlank String firstName, // Заменяет ручную проверку на null и .blank()
    String lastName,
    String phone,
    String telegramId,
    String instagramId,
    @PositiveOrZero int bonusBalance, // Заменяет проверку balance < 0
    LocalDateTime createdAt
) {}
