plugins {
    id("buildlogic.java-library-conventions")
}

group = project.group
version = project.version

dependencies {
    implementation(project(":salon-api"))

    // Подключаем API для декларативных контроллеров
    implementation(libs.avaje.http.api)
    // ДОБАВЛЯЕМ: Сам Jex для компиляции сгенерированных роутеров
    implementation(libs.avaje.jex)
    // Генератор маршрутов Avaje Http для Jex, работающий при компиляции
    annotationProcessor(libs.avaje.http.jex.generator)
    implementation(libs.avaje.jex.ssl)
    implementation(libs.avaje.http.client)
    annotationProcessor(libs.avaje.http.client.generator)

    implementation(libs.avaje.jsonb)
    annotationProcessor(libs.avaje.jsonb.generator)

    // Добавляем в слой данных для чтения проперти
    implementation(libs.avaje.config)

    // Сам зарегистрируется в BeanScope. Поддерживает конфигурацию. Смотри github.
    // Для того, чтобы jsonb можно было конфигурировать, avaje.config должен присутствовать
    // в зависимостях хотя бы runtimeOnly, тогда BeanScopeBuilder его обнаружит и будет
    // использовать application.properties или application-test.properties. Если avaje.config
    // в зависимостях нет, то будут использоваться установки по умолчанию.
    runtimeOnly(libs.avaje.jsonb.inject.plugin)

    // Слой валидации (необходим для фабрики и валидации DTO)
    runtimeOnly(libs.avaje.validator)
    //annotationProcessor(libs.avaje.validator.generator)

    // Сам зарегистрируется в BeanScope. Поддерживает конфигурацию. Смотри github.
    // Для того, чтобы jsonb можно было конфигурировать, avaje.config должен присутствовать
    // в зависимостях хотя бы runtimeOnly, тогда BeanScopeBuilder его обнаружит и будет
    // использовать application.properties или application-test.properties. Если avaje.config
    // в зависимостях нет, то будут использоваться установки по умолчанию.
    runtimeOnly(libs.avaje.validator.inject.plugin)

    // Потребуется для DI-инжекции сервисов в контроллеры
    implementation(libs.avaje.inject)
    annotationProcessor(libs.avaje.inject.generator)

    // TESTING ARTIFACTS
    testImplementation(libs.test.mockito)
    //testImplementation(libs.avaje.inject.test)
}

