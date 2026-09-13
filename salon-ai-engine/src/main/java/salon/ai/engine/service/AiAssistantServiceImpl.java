package salon.ai.engine.service;

import io.avaje.config.Config;
import io.avaje.inject.External;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
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
import salon.api.exception.IntegrityViolationException;
import salon.api.exception.StorageInfrastructureException;
import salon.api.model.DialogueContext;
import salon.api.model.DialogueResponse;
import salon.api.model.LlamaResponse;
import salon.api.model.PlatformType;
import salon.api.model.ProcessMessageCommand;
import salon.api.service.AiAssistantService;
import salon.api.service.ChatMemoryService;

/**
 * <h2>DESIGN INTENT: Clean Architecture Business Boundary Bridge</h2>
 * This component implements the public-facing {@link AiAssistantService} port contract interface
 * declared inside the {@code salon-api} module.
 *
 * <p><b>WHY IT EXISTS:</b>
 * Following the Dependency Inversion Principle (DIP), the web and API layers should never know
 * about the concrete AI frameworks we use. This class acts as a protective shield. It receives
 * standard Java types from the incoming webhook controllers, delegates the interaction to our
 * internal LangChain4j {@link LowLevelAiService} agent, and returns the response back to the
 * caller.</p>
 *
 * <p><b>COMPILATION BOUNDARIES:</b>
 * This component is registered as an SPI extension service provider inside our
 * {@code module-info.java}. This allows the application to remain strictly modular: external
 * consumers can inject and call the {@code AiAssistantService} interface, but they have absolute
 * zero compilation access to the internal Jackson parsing engines, Ollama network links, or
 * LangChain4j agent classes running under the hood.</p>
 */
@Singleton
final class AiAssistantServiceImpl implements AiAssistantService {

  private static final Logger log = LoggerFactory.getLogger(AiAssistantServiceImpl.class);

  private final LowLevelAiService lowLevelAiService;
  private final SalonStateMachineService stateMachine;
  private final ChatMemoryService chatMemory;

  private final ExecutorService aiExecutor = Executors.newVirtualThreadPerTaskExecutor();
  private final Duration pipelineTimeout;

  // Конструктор принимает готовый сервис ИИ.
  // Сам доступ к СУБД (jOOQ DSLContext) инжектируется внутрь BookingTools,
  // которые LangChain4j использует как "руки" для похода в базу данных во время chat-сессии.
  @Inject
  AiAssistantServiceImpl(LowLevelAiService lowLevelAiService, SalonStateMachineService stateMachine,
      @External ChatMemoryService chatMemory) {
    this.lowLevelAiService = lowLevelAiService;
    this.stateMachine = stateMachine;
    this.chatMemory = chatMemory;
    // Формат времени ISO 8601
    pipelineTimeout = Config.getDuration("ai.model.request.timeout", "PT4M")
        .plus(Config.getDuration("ai.assistant.pipeline.timeout.increment", "PT5S"));
  }

  @Override
  public String processChat(ProcessMessageCommand command) {
    log.info("AI Hub: Запуск детерминированного конвейера для Trace ID: [{}]", command.traceId());

    final PlatformType platformType = command.platformType();
    final String platformId = command.platformId();

    // Асинхронно отправляем задачу в пулл виртуальных потоков Java 21
    Future<String> aiTask = aiExecutor.submit(() -> {
      // Шаг 1: Восстановление контекста сессии из СУБД по составному натуральному ключу
      DialogueContext context = chatMemory.findContextByClientId(platformType, platformId)
          .orElseGet(() -> {
            log.info("AI Hub: Первое обращение. Инициализация стейта 'INIT' для Trace ID: [{}]",
                command.traceId());
            return DialogueContext.createNew(platformId);
          });

      // Шаг 2: Извлечение интента и слотов силами Llama3 (Изолированный сетевой I/O запрос)
      LlamaResponse parsedData = lowLevelAiService.extractIntentAndSlots(command.messageText());

      // Шаг 3: Вычисление бизнес-правил переходов стейт-машины диалога на стороне Java
      DialogueResponse stateResponse = stateMachine.processTurn(parsedData, context);

      // Шаг 4: Атомарное сохранение контекста обратно в СУБД (коммит транзакции слотов)
      chatMemory.saveContext(platformType, platformId, stateResponse.updatedContext());

      // Возвращаем чистый текст ответа для отправки на экран мобильного телефона клиента
      return stateResponse.replyMessage();
    });

    try {
      return aiTask.get(pipelineTimeout.toSeconds(), TimeUnit.SECONDS);

    } catch (TimeoutException timeoutEx) {
      aiTask.cancel(true);
      log.error("AI Hub: Превышен лимит ожидания ответа ИИ ({}s) для Trace ID: [{}]",
          pipelineTimeout, command.traceId());
      throw new AiEngineException("Нейросетевое ядро не ответило за отведенное время (Timeout).",
          timeoutEx);

    } catch (ExecutionException executionEx) {
      Throwable rootCause = executionEx.getCause();

      // Специфический перехват инфраструктурных ошибок СУБД, проброшенных из ChatMemoryService
      if (rootCause instanceof StorageInfrastructureException) {
        log.error("AI Hub: Критический сбой PostgreSQL для Trace ID: [{}]", command.traceId(),
            rootCause);
        throw new AiEngineException("Внутренняя ошибка базы данных при сохранении памяти диалога.",
            rootCause);
      }

      if (rootCause instanceof IntegrityViolationException) {
        log.error("AI Hub: Нарушение реляционной целостности для Trace ID: [{}]", command.traceId(),
            rootCause);
        throw new AiEngineException(
            "Конфликт уникальных ключей профиля клиента при фиксации состояния.", rootCause);
      }

      log.error("AI Hub: Критический сбой ИИ-агента для Trace ID: [{}]", command.traceId(),
          rootCause);
      throw new AiEngineException("Внутренний сбой фабрики ИИ при обработке бизнес-контекста.",
          rootCause);

    } catch (InterruptedException interruptedException) {
      Thread.currentThread().interrupt();
      log.error("AI Hub: Поток выполнения был аварийно прерван ОС для Trace ID: [{}]",
          command.traceId());
      throw new AiEngineException("Процесс генерации ответа прерван инфраструктурой.",
          interruptedException);
    }
  }
}
