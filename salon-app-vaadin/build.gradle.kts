plugins {
    id("buildlogic.java-application-conventions")
}

group = project.group
version = project.version

dependencies {
    implementation(project(":salon-api"))
    implementation(project(":salon-db-jooq"))
    implementation(project(":salon-ai-engine"))

    // Фронтенд интерфейс
    implementation(libs.vaadin.core)

    // Серверная часть для приема Вебхуков (Jex + HTTP API)
    implementation(libs.avaje.jex)
    implementation(libs.avaje.http.api)
    // Процессор, который на этапе компиляции свяжет аннотации @Controller с сервером Jex
    annotationProcessor(libs.avaje.http.jex.generator)

    // Compile-time DI через Avaje
    implementation(libs.avaje.inject)
    annotationProcessor(libs.avaje.inject.generator)
}
