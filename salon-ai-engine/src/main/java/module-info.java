module salon.ai.engine {
  requires io.avaje.config;
  requires io.avaje.inject;
  requires org.slf4j;
  requires salon.api;

  // THE VICTORY LINK: Grants direct visibility into the leaked transitive module graph
  requires com.fasterxml.jackson.annotation;
  requires com.fasterxml.jackson.core;
  requires com.fasterxml.jackson.databind;
  //requires langchain4j;
  //requires langchain4j.core;
  //requires langchain4j.core;

  provides io.avaje.inject.spi.InjectExtension with salon.ai.engine.EngineModule;
}
