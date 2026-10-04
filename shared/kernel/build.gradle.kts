// Shared kernel: identifiers, time, contract enums and the error envelope.
// Used by Control Plane and, later, execution modules, so it stays free of Spring and DB libraries.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.jackson.databind)
    testRuntimeOnly(libs.junit.platform.launcher)
}
