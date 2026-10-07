// Privacy data-subject execution: export, erasure and retention sweeps (T14 ops, 14, 25, ADR 0013).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":shared:kernel"))
    implementation(project(":control-plane:identity"))
    implementation(project(":control-plane:platform"))
    implementation(project(":control-plane:evidence"))
    implementation(project(":control-plane:lab"))
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
}
