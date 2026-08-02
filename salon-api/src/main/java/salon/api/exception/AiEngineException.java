package salon.api.exception;

/**
 * <h2>Исключение нейросетевого ядра ИИ-ассистента</h2>
 * Выбрасывается при отказах генерации ответов: тайм-аутах Ollama/OpenAI,
 * превышении лимитов контекстного окна токенов или сбоях LangChain4j.
 */
public class AiEngineException extends SalonException {

  public AiEngineException(String message) {
    super(message);
  }

  public AiEngineException(String message, Throwable cause) {
    super(message, cause);
  }
}
