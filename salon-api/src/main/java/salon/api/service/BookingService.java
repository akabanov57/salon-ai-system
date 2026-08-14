package salon.api.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import salon.api.model.Appointment;
import salon.api.model.CatalogService;
import salon.api.model.Master;
import salon.api.model.ProcessMessageCommand;

/**
 * Высокоуровневый бизнес-ориентированный порт (Use Case) для управления процессами салона.
 * Единственная точка входа для ИИ-движка, вебхуков и GUI.
 */
public interface BookingService {

  /**
   * Идентифицирует клиента по Telegram ID или создает новый профиль, если он пишет впервые.
   * Реализует атомарный шаг Use Case №1.
   */
  void processMessage(ProcessMessageCommand command);

  /**
   * Предварительное резервирование временного слота ИИ-ассистентом.
   *
   * <p>Метод полностью скрывает существование автоинкрементных числовых ключей СУБД,
   * оперируя исключительно строковыми бизнес-контекстами естественного языка.</p>
   *
   * @param platformId      Идентификатор мессенджера пользователя (chat_id)
   * @param masterAlias     Уникальный текстовый псевдоним выбранного стилиста
   * @param serviceName     Официальное наименование запрашиваемой процедуры
   * @param appointmentTime Целевое время начала сеанса визита
   * @return Доменный объект записи визита в статусе AI_PENDING в случае успешного бронирования
   */
  Optional<Appointment> tryAiBooking(
      String platformId,
      String masterAlias,
      String serviceName,
      LocalDateTime appointmentTime
  );

  /**
   * <h3>Бизнес-метод: Получение всех мастеров, работающих в выбранный день</h3>
   * Позволяет ИИ-ассистенту ориентировать клиентов по доступному на сегодня штату специалистов.
   */
  List<Master> getActiveMastersForDate(LocalDateTime date);

  /**
   * <h3>Бизнес-метод: Проверка фактической занятости и доступности мастера</h3>
   * Вычисляет, находится ли мастер на рабочей смене и свободен ли запрашиваемый временной интервал.
   */
  boolean isMasterAvailableAt(Long masterId, Long serviceId, LocalDateTime time, int durationMinutes);

  /**
   * Выполняет поиск активных услуг в каталоге по текстовому совпадению.
   * Используется ИИ-ассистентом для извлечения точных идентификаторов услуг.
   *
   * @param keyword Ключевое слово для полнотекстового или LIKE поиска
   * @return Список найденных услуг, соответствующих критерию
   */
  List<CatalogService> searchServicesInCatalog(String keyword);
}
