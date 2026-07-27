module salon.web.http {
  requires io.avaje.http.api;
  requires io.avaje.inject;
  requires io.avaje.jex;
  requires org.slf4j;
  requires salon.api;
  requires io.avaje.jsonb;
  requires io.avaje.config;
  requires io.avaje.jex.ssl;
  requires io.avaje.http.client;
  //requires io.avaje.validation;

  provides io.avaje.inject.spi.InjectExtension with salon.web.http.HttpModule;
  provides io.avaje.http.client.HttpClient.GeneratedComponent with salon.web.http.internal.services.httpclient.GeneratedHttpComponent;
  provides io.avaje.jsonb.spi.JsonbExtension with salon.web.http.jsonb.GeneratedJsonComponent;
}