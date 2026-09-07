plugins {
    id("buildlogic.java-library-conventions")
}

group = project.group
version = project.version

dependencies {
    // Подключаем все физические модули системы для сквозной компиляции
    implementation(project(":salon-api"))
    implementation(project(":salon-db-jooq"))
    implementation(project(":salon-ai-engine"))
    implementation(project(":salon-web-http"))

    testImplementation(libs.avaje.http.client)
    annotationProcessor(libs.avaje.http.client.generator)
    // ДОБАВЛЯЕМ: Сам Jex для компиляции сгенерированных роутеров
    testImplementation(libs.avaje.jex)
    // Генератор маршрутов Avaje Http для Jex, работающий при компиляции
    annotationProcessor(libs.avaje.http.jex.generator)
    testImplementation(libs.avaje.jex.ssl)

    testImplementation(libs.jooq.core)
    // H2 доступен ИСКЛЮЧИТЕЛЬНО во время запуска тестов
    testRuntimeOnly(libs.db.h2)
    testRuntimeOnly(libs.db.hikari)

    testImplementation(libs.avaje.jsonb)
    // Сам зарегистрируется в BeanScope. Поддерживает конфигурацию. Смотри github.
    // Для того, чтобы jsonb можно было конфигурировать, avaje.config должен присутствовать
    // в зависимостях хотя бы runtimeOnly, тогда BeanScopeBuilder его обнаружит и будет
    // использовать application.properties или application-test.properties. Если avaje.config
    // в зависимостях нет, то будут использоваться установки по умолчанию.
    runtimeOnly(libs.avaje.jsonb.inject.plugin)

    // Добавляем в слой данных для чтения проперти
    testImplementation(libs.avaje.config)

    // Compile-time Dependency Injection container
    testImplementation(libs.avaje.inject)
    annotationProcessor(libs.avaje.inject.generator)
}
