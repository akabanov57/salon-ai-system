package salon.api.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
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
  Appointment tryAiBooking(
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
   * Вычисляет, находится ли мастер на рабочей смене и свободен ли запрашиваемый временной
   * интервал.
   */
  boolean isMasterAvailableAt(Long masterId, Long serviceId, LocalDateTime time,
      int durationMinutes);

  /**
   * Выполняет поиск активных услуг в каталоге по текстовому совпадению. Используется ИИ-ассистентом
   * для извлечения точных идентификаторов услуг.
   *
   * @param keyword Ключевое слово для полнотекстового или LIKE поиска
   * @return Список найденных услуг, соответствующих критерию
   */
  List<CatalogService> searchServicesInCatalog(String keyword);

  /**
   * <h3>Бизнес-метод: Получение квалифицированных и свободных мастеров</h3>
   * <p>
   * Выполняет аналитический поиск в СУБД и возвращает список мастеров, которые: 1. Находятся на
   * рабочей смене в указанную дату. 2. Обладают навыком/квалификацией для оказания запрашиваемой
   * услуги. 3. Имеют хотя бы одно свободное временное окно внутри указанного интервала, достаточное
   * для полной длительности этой услуги.
   * </p>
   *
   * @param serviceName Официальное или поисковое наименование процедуры (слот от ИИ).
   * @param date        Целевая дата визита.
   * @param timeFrom    Нижняя граница желаемого интервала времени.
   * @param timeTo      Верхняя граница желаемого интервала времени.
   * @return Список доменных объектов мастеров, готовых принять клиента.
   * @throws IllegalArgumentException если serviceName равен {@code null} или является пустой строкой.
   * @throws salon.api.exception.StorageInfrastructureException если произошел критический сбой СУБД.
   * @throws NullPointerException если date, или timeFrom, или timeTo равен {@code null}.
   */
  List<Master> getAvailableMastersForServiceInterval(
      String serviceName,
      LocalDate date,
      LocalTime timeFrom,
      LocalTime timeTo
  );
}
