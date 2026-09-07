package salon.ai.engine.internal.impl;

import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import salon.ai.engine.internal.service.DateTimeParser;

@Singleton
final class DefaultDateTimeParser implements DateTimeParser {

  // Регулярные выражения для поиска паттернов времени и дней
  private static final Pattern TIME_PATTERN = Pattern.compile("(?<hour>\\d{1,2})[:.-](?<minute>\\d{2})|(?<hourOnly>\\d{1,2})\\s*(?:час|ч|на\\s+)?");
  private static final Pattern DAY_KEYWORD_PATTERN = Pattern.compile("(?<today>сегодн|сейчас)|(?<tomorrow>завтр)|(?<afterTomorrow>послезавтр)");

  private static final Pattern WEEKDAY_PATTERN = Pattern.compile(
      "(?<mon>понед)|(?<tue>вторн)|(?<wed>сред)|(?<thu>четв)|(?<fri>пятн)|(?<sat>субб)|(?<sun>воскр)"
  );

  @Override
  public Optional<LocalDateTime> parseRaw(String rawText) {
    // По умолчанию используем системное время рантайма
    return parseRaw(rawText, LocalDate.now());
  }

  @Override
  public Optional<LocalDateTime> parseRaw(String rawText, LocalDate baseDate) {
    if (rawText == null || rawText.isBlank()) return Optional.empty();

    String input = rawText.toLowerCase().trim();
    LocalDate targetDate = baseDate; // Использование детерминированного базового анкора
    LocalTime targetTime = null;

    // 1. Расчет дня на основе базовой даты
    Matcher dayMatcher = DAY_KEYWORD_PATTERN.matcher(input);
    if (dayMatcher.find()) {
      if (dayMatcher.group("tomorrow") != null) targetDate = targetDate.plusDays(1);
      else if (dayMatcher.group("afterTomorrow") != null) targetDate = targetDate.plusDays(2);
    } else {
      Matcher weekdayMatcher = WEEKDAY_PATTERN.matcher(input);
      if (weekdayMatcher.find()) {
        DayOfWeek targetDayOfWeek = getDayOfWeek(weekdayMatcher);
        if (targetDate.getDayOfWeek() != targetDayOfWeek) {
          targetDate = targetDate.with(TemporalAdjusters.next(targetDayOfWeek));
        }
      }
    }

    // 2. Расчет времени (с исправленным Integer.parseInt)
    Matcher timeMatcher = TIME_PATTERN.matcher(input);
    if (timeMatcher.find()) {
      int hour;
      int minute = 0;
      if (timeMatcher.group("hour") != null) {
        hour = Integer.parseInt(timeMatcher.group("hour"));
        minute = Integer.parseInt(timeMatcher.group("minute"));
      } else {
        hour = Integer.parseInt(timeMatcher.group("hourOnly"));
      }
      if (hour >= 0 && hour < 24 && minute >= 0 && minute < 60) targetTime = LocalTime.of(hour, minute);
    } else {
      if (input.contains("утром")) targetTime = LocalTime.of(10, 0);
      else if (input.contains("после обеда") || input.contains("днем")) targetTime = LocalTime.of(15, 0);
      else if (input.contains("вечером")) targetTime = LocalTime.of(18, 0);
    }

    if (targetTime == null) return Optional.empty();
    return Optional.of(LocalDateTime.of(targetDate, targetTime));
  }

  private DayOfWeek getDayOfWeek(Matcher m) {
    if (m.group("mon") != null) return DayOfWeek.MONDAY;
    if (m.group("tue") != null) return DayOfWeek.TUESDAY;
    if (m.group("wed") != null) return DayOfWeek.WEDNESDAY;
    if (m.group("thu") != null) return DayOfWeek.THURSDAY;
    if (m.group("fri") != null) return DayOfWeek.FRIDAY;
    if (m.group("sat") != null) return DayOfWeek.SATURDAY;
    if (m.group("sun") != null) return DayOfWeek.SUNDAY;
    return DayOfWeek.MONDAY;
  }
}
