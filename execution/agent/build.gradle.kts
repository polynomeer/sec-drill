plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    api(project(":execution:protocol"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
}
