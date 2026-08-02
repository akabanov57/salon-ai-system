module salon.api {
  requires io.avaje.validation.contraints;
  requires io.avaje.jsonb;

  exports salon.api.model;
  exports salon.api.service;
  exports salon.api.exception;

  provides io.avaje.jsonb.spi.JsonbExtension with salon.api.model.jsonb.GeneratedJsonComponent;
}
