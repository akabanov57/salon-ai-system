plugins {
    id("buildlogic.java-library-conventions")
}

group = project.group
version = project.version

dependencies {
    // Подключаем API валидатора
    // Оставляем ТОЛЬКО аннотации. Никаких движков и процессоров аннотаций!
    implementation(libs.avaje.validator.constraints)
    implementation(libs.avaje.jsonb)
    annotationProcessor(libs.avaje.jsonb.generator)
}
