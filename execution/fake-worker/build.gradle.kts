// Development-only fake grading worker. Speaks only the job protocol; never sees the Control DB.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":execution:protocol"))

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}
