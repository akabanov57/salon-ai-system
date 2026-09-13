package salon.web.http.configuration;

import io.avaje.config.Config;
import io.avaje.http.client.HttpClient;
import io.avaje.http.client.JsonbBodyAdapter;
import io.avaje.inject.Bean;
import io.avaje.inject.Factory;
import io.avaje.jex.Jex;
import io.avaje.jex.Routing.HttpService;
import io.avaje.jex.ssl.SslPlugin;
import io.avaje.jsonb.Jsonb;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Factory
final class WebConfiguration {

  private static final Logger log = LoggerFactory.getLogger(WebConfiguration.class);

  /**
   * Builds and configures the centralized Jex execution server instance bean.
   * <p>
   * Зависит строго от абстрактного интерфейса TelegramVerificationService.
   * <p>
   * KEEP ONLY @Bean. Strip out any residual @Inject markers completely.
   *
   * @param httpServices    List of all compile-time processed controller routing endpoints
   *                        harvested automatically out of the current web module.
   * @param telegramService Внутренний контракт для проверки подлинности входящих HTTP-запросов от
   *                        Telegram Bot API.
   */
  @Bean
  Jex jex(List<HttpService> httpServices) {
    final int port = Config.getInt("jex.port", 8443);
    final boolean sslEnabled = Config.getBool("jex.ssl.enabled", false); // ЧИТАЕМ ФЛАГ ВКЛЮЧЕНИЯ SSL
    final String host = Config.get("jex.host","[::]");

    Jex jex = Jex.create().config(jexConfig -> {
      jexConfig.host(host);
      jexConfig.port(port);
    });

    // Настраиваем SSL только если свойство server.ssl.enabled=true
    if (sslEnabled) {
      String resolvedPath = Config.get("jex.ssl.keystorePath", "certs/salon-keystore.p12");
      String resolvedPassword = Config.get("jex.ssl.keystorePassword", "MySecurePassword123");

      var sslPlugin = SslPlugin.create(config -> {
        if (resolvedPath.startsWith("classpath:")) {
          String resourceName = resolvedPath.substring("classpath:".length());
          log.info("[SSL] Testing Profile. Loading embedded classpath stream: '{}'", resourceName);
          config.keystoreFromClasspath(resourceName, resolvedPassword);
        } else {
          String finalPath = null;

          File rawFile = new File(resolvedPath);
          if (rawFile.isAbsolute() && rawFile.exists() && rawFile.isFile()) {
            finalPath = rawFile.getAbsolutePath();
          }

          if (finalPath == null) {
            String[] potentialRoots = {
                System.getProperty("app.home"),
                System.getProperty("user.dir"),
                "/app"
            };

            for (String root : potentialRoots) {
              if (root != null && !root.isBlank()) {
                Path rootPath = Paths.get(root);
                Path r1 = rootPath.resolve(resolvedPath);
                Path r2 = rootPath.resolve("secret").resolve("salon-keystore.p12");

                if (r1.toFile().exists() && r1.toFile().isFile()) {
                  finalPath = r1.toAbsolutePath().toString();
                  break;
                }
                if (r2.toFile().exists() && r2.toFile().isFile()) {
                  finalPath = r2.toAbsolutePath().toString();
                  break;
                }

                Path parentPath = rootPath.getParent();
                if (parentPath != null) {
                  Path r3 = parentPath.resolve(resolvedPath);
                  if (r3.toFile().exists() && r3.toFile().isFile()) {
                    finalPath = r3.toAbsolutePath().toString();
                    break;
                  }
                }
              }
            }
          }

          if (finalPath == null) {
            try {
              URI jarUri = WebConfiguration.class.getProtectionDomain()
                  .getCodeSource()
                  .getLocation()
                  .toURI();
              Path jarPath = Paths.get(jarUri);

              if (jarPath.toString().endsWith(".jar")) {
                Path parent1 = jarPath.getParent();
                if (parent1 != null) {
                  Path parent2 = parent1.getParent();
                  if (parent2 != null) {
                    Path checkSecret = parent2.resolve("secret").resolve("salon-keystore.p12");
                    if (checkSecret.toFile().exists()) {
                      finalPath = checkSecret.toAbsolutePath().toString();
                    }
                  }
                }
              }
            } catch (Exception e) {
              log.debug("ProtectionDomain scanning bypassed", e);
            }
          }

          if (finalPath == null) {
            finalPath = new File(resolvedPath).getAbsolutePath();
          }

          log.info("[SSL] Production Profile. Resolved absolute file path: '{}'", finalPath);

          File checkFile = new File(finalPath);
          if (!checkFile.exists() || !checkFile.isFile()) {
            log.error(
                "[SSL] FILE MISSING CRITICAL ERROR: Target certificate not found on disk! "
                    + "Checked: {}",
                finalPath);
          }

          config.keystoreFromPath(finalPath, resolvedPassword);
        }
      });

      // Подключаем SSL-плагин к серверу Jex
      jex.plugin(sslPlugin);
    } else {
      log.info("[SSL] Сервер Jex запускается по обычному протоколу HTTP (SSL отключен).");
    }

    // Монтируем сгенерированные контроллеры маршрутов
    jex.routing(httpServices);

    return jex;
  }

  /**
   * Создает базовый HttpClient для исходящих запросов к Telegram. Автоматически настраивает доверие
   * к сертификатам, если включен SSL. Поскольку наш HttpClient зарегистрирован как полноценный бин
   * через метод @Bean public HttpClient baseHttpClient(...), нам не нужно закрывать его вручную.
   * avaje-inject гарантирует его безопасное закрытие на этапе остановки приложения.
   */
  @Bean
  HttpClient baseHttpClient(Jsonb jsonb) {
    final String baseTargetUrl = Config.get("telegram.api.baseUrl", "https://api.telegram.org");
    // Формат времени ISO 8601
    final Duration requestTimeout = Config.getDuration("telegram.api.request-timeout", "PT30S");
    final boolean sslEnabled = Config.getBool("jex.ssl.enabled", false);

    log.info("Инициализация исходящего шлюза Avaje HttpClient: {} с тайм-аутом: {}", baseTargetUrl, requestTimeout);

    final var builder = HttpClient.builder()
        .requestTimeout(requestTimeout)
        .baseUrl(baseTargetUrl)
        .bodyAdapter(new JsonbBodyAdapter(jsonb));

    // ИСПРАВЛЕНО: Если SSL включен (например, для локального прокси или тестирования),
    // мы можем настроить HttpClient на доверие к нашему хранилищу сертификатов.
    if (sslEnabled) {
      log.info("[HttpClient SSL] Настройка безопасного контекста для исходящих вызовов...");
      try {
        // В простейшем случае, если мы шлем запросы на официальный https://api.telegram.org,
        // стандартного SSLContext.getDefault() более чем достаточно.
        // Но если вам нужно доверять именно САМОПОДПИСАННЫМ сертификатам вашего локального
        // окружения,
        // здесь инициализируется SSLContext на основе нашего salon-keystore.p12:

        // javax.net.ssl.SSLContext sslContext = ... (код загрузки нашего хранилища)
        // builder.sslContext(sslContext);

        // Для официального API Telegram оставляем дефолтный защищенный контекст JVM:
        builder.sslContext(javax.net.ssl.SSLContext.getDefault());
      } catch (Exception e) {
        log.error("Не удалось инициализировать SSLContext для HttpClient: {}", e.getMessage());
      }
    }

    return builder.build();
  }
}
