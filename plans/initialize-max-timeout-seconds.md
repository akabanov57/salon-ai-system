# Plan: Initialize maxTimeoutSeconds via Avaje Configuration

## Objective
Initialize `maxTimeoutSeconds` in [`AiAssistantServiceImpl.processChat()`](salon-ai-engine/src/main/java/salon/ai/engine/service/AiAssistantServiceImpl.java:65) using Avaje Config (`io.avaje.config.Config.getInt("ai.model.timeout-seconds", 240)`), consistent with [`AiEngineConfigurationFactory.java`](salon-ai-engine/src/main/java/salon/ai/engine/config/AiEngineConfigurationFactory.java:31).

## Steps
1. Import `io.avaje.config.Config` in [`AiAssistantServiceImpl.java`](salon-ai-engine/src/main/java/salon/ai/engine/service/AiAssistantServiceImpl.java:3).
2. Replace hardcoded `final int maxTimeoutSeconds = 240;` with `final int maxTimeoutSeconds = Config.getInt("ai.model.timeout-seconds", 240);` in [`AiAssistantServiceImpl.java`](salon-ai-engine/src/main/java/salon/ai/engine/service/AiAssistantServiceImpl.java:68).
3. Run test suite using Gradle (`./gradlew test`) to verify correctness.
