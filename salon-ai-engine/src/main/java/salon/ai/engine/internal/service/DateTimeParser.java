package salon.ai.engine.internal.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

public interface DateTimeParser {

  /**
   * Парсит сырую строку времени от пользователя в LocalDateTime
   * @param rawText Например: "в пятницу в 15:00", "завтра в 12:30"
   * @return Optional с вычисленной датой и временем
   */
  Optional<LocalDateTime> parseRaw(String rawText);

  /**
   * Парсит сырую строку времени относительно переданной базовой даты
   */
  Optional<LocalDateTime> parseRaw(String rawText, LocalDate baseDate);
}
