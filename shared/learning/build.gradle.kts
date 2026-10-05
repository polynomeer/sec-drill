// Skill projection and recommendation policies (10, 23, T12). Pure and deterministic: no DB, no Spring, no I/O.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":shared:kernel"))

    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}
