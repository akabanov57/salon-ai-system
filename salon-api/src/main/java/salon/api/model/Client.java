package salon.api.model;

import io.avaje.validation.constraints.Max;
import io.avaje.validation.constraints.NotBlank;
import io.avaje.validation.constraints.NotNull;
import io.avaje.validation.constraints.PositiveOrZero;
import java.time.LocalDateTime;

/**
 * <h2>Доменная модель: Унифицированный аккаунт клиента мессенджера</h2>
 * <p>
 * Полностью нормализована и очищена от избыточных полей.
 * Проверка полей на уровне Java-кода выполняется декларативно через Avaje Validation.
 * </p>
 */
public record Client(
    Long id,                        // Суррогатный первичный ключ СУБД

    @NotBlank(message = "Platform type classifier cannot be blank.")
    String platformType,            // Тип мессенджера (TELEGRAM, INSTAGRAM)

    @NotBlank(message = "Platform sender identity handle cannot be blank.")
    String platformId,              // Натуральный ID пользователя внутри конкретной сети

    @NotBlank(message = "Client display name placeholder cannot be blank.")
    String displayName,             // Имя или никнейм, отображаемый на платформе (дефолт: 'Guest')

    @PositiveOrZero(message = "Баланс бонусного счета не может быть отрицательным.")
    @Max(value = 100000, message = "Превышен максимальный лимит бонусного баланса (100,000).")
    int bonusBalance,               // Баланс реферальной/бонусной программы салона

    @NotNull(message = "Creation timestamp cannot be null.")
    LocalDateTime createdAt         // Таймштамп первоначальной фиксации профиля в системе
) {}
