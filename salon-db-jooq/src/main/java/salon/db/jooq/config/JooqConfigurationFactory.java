package salon.db.jooq.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.avaje.config.Config;
import io.avaje.inject.Bean;
import io.avaje.inject.Factory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Фабрика Avaje-Inject для сборки и конфигурации jOOQ рантайма.
 * Работает полностью на этапе компиляции.
 */
@Factory
final class JooqConfigurationFactory implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(JooqConfigurationFactory.class);

  // Сохраняем ссылку на инстанс пула, чтобы закрыть его в @PreDestroy
  private HikariDataSource hikariDataSource;

  /**
   * Внутренний метод автоматического развертывания структуры таблиц из ресурсов модуля.
   */
  private void autoMigrateSchema(final DataSource ds) {
    // Проверяем, нужно ли запускать встроенную миграцию (включаем по умолчанию для H2 в тестах)
    final boolean enabled = Config.getBool("db.auto-migrate", true);
    if (!enabled) {
      return;
    }

    log.info("Запуск автоматической сборки структуры таблиц СУБД...");
    try (final InputStream is = getClass().getResourceAsStream("/db/migration/V1__init.sql")) {
      if (is == null) {
        throw new IllegalStateException("Критическая ошибка сборки: файл V1__init.sql не найден в ресурсах класспасса.");
      }

      final String ddlScript = new String(is.readAllBytes(), StandardCharsets.UTF_8);

      // ИСПРАВЛЕНО: Закрываем ТОЛЬКО java.sql.Connection, а не сам DataSource!
      try (java.sql.Connection conn = ds.getConnection()) {
        DSL.using(conn).execute(ddlScript);
        log.info("Структура базы данных успешно инициализирована.");
      }
    } catch (Exception e) {
      // ИСПРАВЛЕНО: Явно пишем фатальную ошибку в лог со всеми деталями и stack trace
      log.error("КРИТИЧЕСКИЙ СБОЙ: Автоматическая миграция структуры БД завершилась ошибкой. " +
          "Приложение не может быть запущено. Причина: {}", e.getMessage(), e);

      throw new RuntimeException("Фатальный сбой автоматической миграции схемы данных: " + e.getMessage(), e);
    }
  }

  @Bean
  DataSource dataSource() {

    // Читаем свойства через avaje-config API с дефолтными значениями
    final String url = Config.get("db.url", "jdbc:postgresql://localhost:5432/salon");
    final String username = Config.get("db.username", "postgres");
    final String password = Config.get("db.password", "postgres");
    final int maxPoolSize = Config.getInt("db.pool.max-size", 10);

    log.info("Инициализация пула соединений HikariCP для URL: {}", url);

    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername(username);
    config.setPassword(password);
    config.setMaximumPoolSize(maxPoolSize);

    config.addDataSourceProperty("cachePrepStmts", "true");
    config.addDataSourceProperty("prepStmtCacheSize", "250");
    config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

    hikariDataSource = new HikariDataSource(config);

    // ИСПРАВЛЕНИЕ: Автоматическая инициализация схемы DDL (как в ai-demo!)
    autoMigrateSchema(hikariDataSource);

    return hikariDataSource;
  }

  /**
   * Создает и регистрирует DSLContext в DI-контейнере.
   *
   * @param dataSource Источник данных (будет автоматически заинжекчен из пуста соединений,
   *                   который мы настроим в главном модуле приложения)
   */
  @Bean
  DSLContext dslContext(DataSource dataSource) {

    // Читаем диалект
    String dialectStr = Config.get("db.dialect", "POSTGRESQL");

    SQLDialect dialect = SQLDialect.valueOf(dialectStr.toUpperCase());
    return DSL.using(dataSource, dialect);
  }

  /**
   * Хук жизненного цикла Avaje-Inject.
   * Вызывается автоматически при остановке контейнера (в тестах или при SIGTERM в продакшене).
   * Обеспечивает Graceful Shutdown пула соединений.
   */
  @Override
  public void close() {
    if (hikariDataSource != null && !hikariDataSource.isClosed()) {
      log.info("Мягкая остановка слоя данных: закрытие пула HikariCP...");
      hikariDataSource.close();
      log.info("Пул HikariCP успешно остановлен.");
    }
  }
}
