package salon.ai.engine.internal.impl;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import salon.ai.engine.internal.service.DateTimeParser;

public class DefaultDateTimeParserTest {

  private DateTimeParser parser;

  @BeforeEach
  void setUp() {
    // Создаем экземпляр напрямую без поднятия DI-контекста для быстроты юнит-тестов
    parser = new DefaultDateTimeParser();
  }

  @Test
  @DisplayName("Должен успешно распарсить точную дату и время: пятница 16:30")
  void shouldParseExactDateTime() {
    final LocalDate today = LocalDate.of(2026,8, 31);
    final String rawText = "на пятницу в 16:30";

    final Optional<LocalDateTime> result = parser.parseRaw(rawText, today);

    assertTrue(result.isPresent(), "Дата должна быть успешно распарсена");
    // Базовая дата: 31.08.2026 (Пн). Ближайшая пятница — 04.09.2026
    assertEquals(2026, result.get().getYear());
    assertEquals(9, result.get().getMonthValue()); // Сентябрь
    assertEquals(4, result.get().getDayOfMonth());
    assertEquals(16, result.get().getHour());
    assertEquals(30, result.get().getMinute());
  }

  @ParameterizedTest
  @CsvSource({
      "сегодня в 12:00, 2026, 8, 31, 12, 0",
      "завтра в 15-45, 2026, 9, 1, 15, 45",
      "послезавтра в 10 час, 2026, 9, 2, 10, 0"
  })
  @DisplayName("Должен корректно обрабатывать относительные ключевые слова (сегодня/завтра)")
  void shouldParseRelativeDayKeywords(String input, int year, int month, int day, int hour, int minute) {
    // Явно привязываем тест к 31 августа 2026 года (Понедельник)
    LocalDate fixedTestAnchor = LocalDate.of(2026, 8, 31);

    Optional<LocalDateTime> result = parser.parseRaw(input, fixedTestAnchor);

    assertTrue(result.isPresent());
    assertEquals(year, result.get().getYear());
    assertEquals(month, result.get().getMonthValue());
    assertEquals(day, result.get().getDayOfMonth());
    assertEquals(hour, result.get().getHour());
    assertEquals(minute, result.get().getMinute());
  }

  @ParameterizedTest
  @CsvSource({
      "утром в понедельник, 2026, 8, 31, 10, 0", // Текущий день, Пн
      "в среду после обеда, 2026, 9, 2, 15, 0",
      "вечером в субботу, 2026, 9, 5, 18, 0"
  })
  @DisplayName("Должен мапить размытые бизнес-понятия времени (утро/вечер) на дефолтные часы")
  void shouldMapVagueSalonTimeWindows(String input, int year, int month, int day, int hour, int minute) {
    // Явно привязываем тест к 31 августа 2026 года (Понедельник)
    LocalDate fixedTestAnchor = LocalDate.of(2026, 8, 31);

    Optional<LocalDateTime> result = parser.parseRaw(input, fixedTestAnchor);

    assertTrue(result.isPresent());
    assertEquals(year, result.get().getYear());
    assertEquals(month, result.get().getMonthValue());
    assertEquals(day, result.get().getDayOfMonth());
    assertEquals(hour, result.get().getHour());
    assertEquals(minute, result.get().getMinute());
  }

  @Test
  @DisplayName("Должен возвращать Optional.empty() на некорректный или пустой ввод")
  void shouldReturnEmptyOnInvalidInput() {
    assertAll(
        () -> assertTrue(parser.parseRaw("").isEmpty()),
        () -> assertTrue(parser.parseRaw(null).isEmpty()),
        () -> assertTrue(parser.parseRaw("просто привет как дела").isEmpty()),
        // День есть, но время вычленить невозможно:
        () -> assertTrue(parser.parseRaw("в пятницу когда-нибудь").isEmpty())
    );
  }
}
