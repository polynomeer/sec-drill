// Lab lifecycle (T04/T06): desired state, quota, TTL, termination, runner protocol and gateway queries (13, 16, 17, ADR 0007).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    api(project(":execution:protocol"))
    implementation(project(":control-plane:identity"))
    implementation(project(":control-plane:platform"))
    implementation(project(":control-plane:evidence"))
    implementation(project(":control-plane:ctf"))
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
}
