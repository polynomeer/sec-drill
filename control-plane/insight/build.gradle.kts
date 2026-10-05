// Reports, skill projection and recommendations (10, 22, 23, T12, ADR 0012).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    api(project(":shared:learning"))
    implementation(project(":execution:simulation"))
    implementation(project(":control-plane:identity"))
    implementation(project(":control-plane:platform"))
    implementation(project(":control-plane:evidence"))
    implementation(project(":control-plane:submission"))
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
}
