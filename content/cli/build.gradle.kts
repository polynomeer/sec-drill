// Authoring CLI (prompt 06: start with CLI and internal APIs). Signs and checks bundles on author machines and CI.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass = "secdrill.content.cli.ContentCliKt"
    applicationName = "content"
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":content:format"))

    testImplementation(testFixtures(project(":content:format")))
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}
