// CTF flags (09, ADR 0008): per-Session flags bound to a Lab generation, constant-time checks and signed receipts.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    api(project(":shared:kernel"))
    implementation(libs.spring.boot.starter)
    implementation(libs.kotlin.reflect)

    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}
