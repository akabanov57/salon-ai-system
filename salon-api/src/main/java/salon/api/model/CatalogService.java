package salon.api.model;

import io.avaje.validation.constraints.NotBlank;
import io.avaje.validation.constraints.NotNull;
import io.avaje.validation.constraints.Positive;
import io.avaje.validation.constraints.PositiveOrZero;
import io.avaje.validation.constraints.Valid;
import java.math.BigDecimal;

/**
 * <h3>Чистая доменная модель услуги парикмахерской из прайс-листа</h3>
 *
 * <p>Объект-значение (Value Object), описывающий нормативные параметры процедуры.
 * Полностью очищен от суррогатных числовых идентификаторов (ID) базы данных.
 * Бизнес-ключом сущности на верхнем уровне является её уникальное текстовое наименование.</p>
 */
@Valid
public record CatalogService(

    @NotBlank(message = "Официальное наименование услуги не может быть пустым.")
    String name,

    @Positive(message = "Продолжительность услуги должна быть положительным числом.")
    int durationMinutes,

    @NotNull(message = "Финансовая стоимость услуги не может быть null.")
    @PositiveOrZero(message = "Стоимость услуги не может быть отрицательной.")
    BigDecimal price
) {}
