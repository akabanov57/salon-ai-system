package salon.ai.engine.service;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import salon.ai.engine.internal.service.LowLevelAiService;
import salon.api.exception.AiEngineException;
import salon.api.model.ProcessMessageCommand;
import salon.api.service.AiAssistantService;

/**
 * <h2>DESIGN INTENT: Clean Architecture Business Boundary Bridge</h2>
 * This component implements the public-facing {@link AiAssistantService} port contract interface
 * declared inside the {@code salon-api} module.
 *
 * <p><b>WHY IT EXISTS:</b>
 * Following the Dependency Inversion Principle (DIP), the web and API layers should never know
 * about the concrete AI frameworks we use. This class acts as a protective shield. It receives
 * standard Java types from the incoming webhook controllers, delegates the interaction to our internal
 * LangChain4j {@link LowLevelAiService} agent, and returns the response back to the caller.</p>
 *
 * <p><b>COMPILATION BOUNDARIES:</b>
 * This component is registered as an SPI extension service provider inside our {@code module-info.java}.
 * This allows the application to remain strictly modular: external consumers can inject and call the
 * {@code AiAssistantService} interface, but they have absolute zero compilation access to the internal
 * Jackson parsing engines, Ollama network links, or LangChain4j agent classes running under the hood.</p>
 */
@Singleton
final class AiAssistantServiceImpl implements AiAssistantService {

  private static final Logger log = LoggerFactory.getLogger(AiAssistantServiceImpl.class);

  private final LowLevelAiService lowLevelAiService;
  private final ExecutorService aiExecutor = Executors.newVirtualThreadPerTaskExecutor();

  // Конструктор принимает готовый сервис ИИ.
  // Сам доступ к СУБД (jOOQ DSLContext) инжектируется внутрь BookingTools,
  // которые LangChain4j использует как "руки" для похода в базу данных во время chat-сессии.
  @Inject
  AiAssistantServiceImpl(LowLevelAiService lowLevelAiService) {
    this.lowLevelAiService = lowLevelAiService;
  }

  @Override
  public String processChat(ProcessMessageCommand command) {
    log.info("AI Hub: Запуск prompt-синтеза для операции Trace ID: [{}]", command.traceId());

    final int maxTimeoutSeconds = 15;

    // Асинхронно отправляем команду в пулл виртуальных потоков Java 21
    Future<String> aiTask = aiExecutor.submit(() -> {
      // Внутри этого вызова LangChain4j берет текст 'command.messageText()',
      // при необходимости обращается к Java-методам @Tool (которые идут в БД через jOOQ),
      // и возвращает честный, подкрепленный фактами ответ.
      return lowLevelAiService.chat(command.platformId(), command.messageText());
    });

    try {
      return aiTask.get(maxTimeoutSeconds, TimeUnit.SECONDS);

    } catch (TimeoutException timeoutEx) {
      aiTask.cancel(true);
      log.error("AI Hub: Превышен лимит ожидания ответа ИИ ({}s) для Trace ID: [{}]", maxTimeoutSeconds, command.traceId());
      throw new AiEngineException("Нейросетевое ядро не ответило за отведенное время (Timeout).", timeoutEx);

    } catch (ExecutionException executionEx) {
      Throwable rootCause = executionEx.getCause();
      log.error("AI Hub: Критический сбой ИИ-агента для Trace ID: [{}]", command.traceId(), rootCause);
      throw new AiEngineException("Внутренний сбой фабрики ИИ при обработке бизнес-контекста.", rootCause);

    } catch (InterruptedException interruptedException) {
      Thread.currentThread().interrupt();
      log.error("AI Hub: Поток выполнения был аварийно прерван ОС для Trace ID: [{}]", command.traceId());
      throw new AiEngineException("Процесс генерации ответа прерван инфраструктурой.", interruptedException);
    }
  }
}
