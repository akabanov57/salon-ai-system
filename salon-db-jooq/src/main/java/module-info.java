module salon.db.jooq {
  requires com.zaxxer.hikari;
  requires io.avaje.config;
  requires io.avaje.inject;
  requires jakarta.inject;
  requires java.sql;
  requires org.jooq;
  requires org.slf4j;
  requires salon.api;

  provides io.avaje.inject.spi.InjectExtension with salon.db.jooq.JooqModule;
}