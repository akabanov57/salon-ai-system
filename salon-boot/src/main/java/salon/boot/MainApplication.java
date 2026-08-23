package salon.boot;

import io.avaje.inject.BeanScope;
import io.avaje.jex.Jex;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import salon.api.service.TelegramWebhookInitializer;

public class MainApplication {

  private static final Logger log = LoggerFactory.getLogger(MainApplication.class);

  static void main() {
    log.info("Запуск инициализации compile-time DI-контейнера приложения...");

    // Синхронизатор для блокировки основного потока main во время работы приложения
    final CountDownLatch latch = new CountDownLatch(1);

    // FIXED: Синхронизатор для блокировки хука. Он заставит JVM подождать,
    // пока поток main полностью выйдет из try-with-resources и запишет ВСЕ логи закрытия пула!
    final CountDownLatch mainExecutionLatch = new CountDownLatch(1);

    try (BeanScope scope = BeanScope.builder().build()) {

      Jex jex = scope.get(Jex.class);
      Jex.Server server = jex.start();
      log.info("Микросервис успешно запущен и готов к работе.");

      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        log.info("Получен сигнал завершения работы ОС (SIGTERM). Запуск Graceful Shutdown...");
        try {
          log.info("Остановка веб-сервера Avaje Jex: закрытие сетевых сокетов...");
          server.shutdown();
          log.info("Веб-сервер успешно остановлен.");
        } catch (Exception e) {
          log.error("Ошибка при остановке веб-сервера:", e);
        } finally {
          // Разблокируем поток main, заставляя его выйти из блока try-with-resources
          latch.countDown();
        }

        // FIXED: Поток хука блокируется и ждет, пока поток main штатно уничтожит BeanScope и залогирует закрытие СУБД
        try {
          mainExecutionLatch.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }, "shutdown-hook-thread"));

      // Вызов Способа Б: Контейнер создает новый временный инстанс Prototype-бина,
      // выполняет метод и отпускает ссылку
      final TelegramWebhookInitializer initializer = scope.get(TelegramWebhookInitializer.class);
      initializer.registerWebhook();

      // Блокируем поток main, чтобы приложение не завершилось мгновенно
      latch.await();

    } catch (InterruptedException e) {
      log.warn("Основной поток приложения был прерван.");
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      log.error("Критическая ошибка во время работы приложения:", e);
    } finally {
      // FIXED: Точка полной безопасности. Мы вышли из try-with-resources,
      // BeanScope закрыт, пул Hikari остановлен, логи записаны. Отпускаем хук ОС!
      mainExecutionLatch.countDown();
    }

    log.info("Процесс Graceful Shutdown успешно завершен. Приложение остановлено со статусом 0.");
  }
}
