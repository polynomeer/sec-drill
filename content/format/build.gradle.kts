// Content bundle format (08, ADR 0006): digests, Ed25519 signatures, static validation and leak scanning.
// Shared by the authoring CLI and the Control Plane; no Spring, no DB.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    api(project(":shared:kernel"))
    api(libs.jackson.databind)

    testFixturesImplementation(platform(libs.spring.boot.bom))
    testFixturesApi(libs.jackson.databind)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}
