package salon.api.model;

import io.avaje.validation.constraints.NotBlank;
import io.avaje.validation.constraints.Valid;

/**
 * <h3>Чистая доменная модель мастера салона красоты</h3>
 *
 * <p>Объект-значение или сущность верхнего абстрактного уровня домена.
 * Полностью очищен от инфраструктурного числового идентификатора (Long id) СУБД,
 * предотвращая утечку суррогатных ключей за границы слоя персистентности.</p>
 */
@Valid
public record Master(
    @NotBlank(message = "Уникальный разговорный псевдоним мастера (alias) не может быть пустым.")
    String alias, // Естественный бизнес-ключ для ИИ и внешних интерфейсов (например, 'elena_colorist')

    @NotBlank(message = "Имя мастера не может быть пустым.")
    String firstName,

    @NotBlank(message = "Фамилия мастера не может быть пустой.")
    String lastName,

    String specialization // Может быть изначально пустым (например, для стажеров)
) {
  /**
   * Возвращает полное удобочитаемое имя сотрудника.
   */
  public String getFullName() {
    return firstName + " " + lastName;
  }
}
