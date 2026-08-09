package salon.db.jooq.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static salon.db.jooq.generated.Tables.SALON_CALENDAR_EXCEPTIONS;
import static salon.db.jooq.generated.Tables.SALON_WEEKLY_SCHEDULE;

import io.avaje.inject.test.InjectTest;
import jakarta.inject.Inject;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import salon.api.model.SalonDayWindow;
import salon.api.service.SalonScheduleProvider;

/**
 * <h3>Интеграционный тест проверки Fallback/Override алгоритма работы салона</h3>
 *
 * Аннотация {@code @InjectTest} автоматически инициализирует тестовый контекст Avaje,
 * поднимает базу данных H2 и внедряет зависимости без раздувания бойлерплейт-кода.
 */
@InjectTest
public class SalonScheduleProviderFallbackOverrideTest {

  @Inject
  public SalonScheduleProvider salonScheduleProvider; // Внедряем реальный тестируемый сервис

  @Inject
  public DSLContext dslCtx; // Внедряем живой jOOQ контекст тестовой базы H2

  @BeforeEach
  void setUpCleanIsolatedH2State() {
    // Жесткая изоляция данных: очищаем таблицы перед каждым тестовым методом
    dslCtx.truncate(SALON_CALENDAR_EXCEPTIONS).execute();
    dslCtx.truncate(SALON_WEEKLY_SCHEDULE).execute();

    // Заполняем базовый шаблон: Понедельник работает с 09:00 до 21:00
    dslCtx.insertInto(SALON_WEEKLY_SCHEDULE)
        .set(SALON_WEEKLY_SCHEDULE.DAY_OF_WEEK, DayOfWeek.MONDAY.name())
        .set(SALON_WEEKLY_SCHEDULE.IS_CLOSED, false)
        .set(SALON_WEEKLY_SCHEDULE.OPEN_TIME, LocalTime.of(9, 0))
        .set(SALON_WEEKLY_SCHEDULE.CLOSE_TIME, LocalTime.of(21, 0))
        .execute();
  }

  /**
   * <h3>Тест 1 (Fallback Pass): Плавный откат к базовому шаблону дня недели</h3>
   *
   * <p><b>Бизнес-контекст:</b> Менеджер или ИИ-ассистент запрашивает рабочее окно салона
   * на стандартный, будний понедельник. Никаких праздников, переносов или санитарных
   * исключений на эту дату в календаре изменений не зарегистрировано [Strict Grounding].</p>
   *
   * <p><b>Ход выполнения сценария:</b>
   * <ol>
   *   <li>Поиск в таблице исключений {@code SALON_CALENDAR_EXCEPTIONS} возвращает пустой маркер (NULL).</li>
   *   <li>SQL-функция {@code COALESCE} на уровне ядра базы данных отбрасывает пустой результат [Strict Grounding].
   *   <li>Система автоматически откатывается к базовому циклическому регламенту дня недели из таблицы
   *       {@code SALON_WEEKLY_SCHEDULE} [Strict Grounding].</li>
   *   <li>Провайдер возвращает стандартные зафиксированные часы работы заведения (09:00 - 21:00) [Strict Grounding].</li>
   * </ol>
   * </p>
   */
  @Test
  void shouldReturnWeeklyDefaultScheduleWhenNoCalendarExceptionIsPresent() {
    // Arrange: 10 августа 2026 года — это обычный рабочий понедельник
    LocalDate standardMonday = LocalDate.of(2026, 8, 10);

    // Act
    SalonDayWindow window = salonScheduleProvider.getWorkingWindowFor(standardMonday);

    // Assert
    assertNotNull(window);
    assertFalse(window.isClosed());
    assertEquals(LocalTime.of(9, 0), window.openTime());
    assertEquals(LocalTime.of(21, 0), window.closeTime());
  }

  /**
   * <h3>Тест 2 (Override Pass): Принудительное замещение дефолтных часов календарным исключением</h3>
   *
   * <p><b>Бизнес-контекст:</b> Владелец салона установил на конкретную дату специальный
   * сокращенный график (например, праздничный или предпраздничный день) [Strict Grounding].</p>
   *
   * <p><b>Ход выполнения сценария:</b>
   * <ol>
   *   <li>В таблицу {@code SALON_CALENDAR_EXCEPTIONS} вносится точечная мутация под выбранное число.</li>
   *   <li>При запросе этого дня {@code LEFT JOIN} находит измененную строку.</li>
   *   <li>SQL-функция {@code COALESCE} фиксирует не-NULL значение из таблицы исключений,
   *       полностью блокируя и перекрывая (Override) стандартные шаблоны [Strict Grounding].</li>
   *   <li>Провайдер возвращает измененные часы, гарантируя, что система и ИИ увидят именно сокращенное время [Strict Grounding].</li>
   * </ol>
   * </p>
   */
  @Test
  void shouldOverrideWeeklyDefaultsWhenCalendarExceptionIsExplicitlyRegistered() {
    // Arrange: 10 августа 2026 года. Принудительно вставляем праздничное исключение (сокращенный день)
    LocalDate exceptionalMonday = LocalDate.of(2026, 8, 10);

    dslCtx.insertInto(SALON_CALENDAR_EXCEPTIONS)
        .set(SALON_CALENDAR_EXCEPTIONS.CALENDAR_DATE, exceptionalMonday)
        .set(SALON_CALENDAR_EXCEPTIONS.IS_CLOSED, false)
        .set(SALON_CALENDAR_EXCEPTIONS.OPEN_TIME, LocalTime.of(10, 0))
        .set(SALON_CALENDAR_EXCEPTIONS.CLOSE_TIME, LocalTime.of(15, 0))
        .execute();

    // Act
    SalonDayWindow window = salonScheduleProvider.getWorkingWindowFor(exceptionalMonday);

    // Assert
    assertNotNull(window);
    assertFalse(window.isClosed());
    assertEquals(LocalTime.of(10, 0), window.openTime(), "Запрос обязан выдать сокращенное время открытия.");
    assertEquals(LocalTime.of(15, 0), window.closeTime(), "Запрос обязан выдать сокращенное время закрытия.");
  }
}
