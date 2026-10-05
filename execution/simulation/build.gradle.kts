// Detection DSL evaluation and the incident-response model (21, 22, T10). Pure and deterministic: no DB, no Spring, no I/O.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    api(project(":shared:kernel"))
    api(libs.jackson.databind)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlin.reflect)
    testRuntimeOnly(libs.junit.platform.launcher)
}
