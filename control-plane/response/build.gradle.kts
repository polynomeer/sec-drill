// Incident-response model actions and the detection training dataset (21, 22, T10, ADR 0010).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    api(project(":execution:simulation"))
    implementation(project(":control-plane:identity"))
    implementation(project(":control-plane:platform"))
    implementation(project(":control-plane:evidence"))
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
}
