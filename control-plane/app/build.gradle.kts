// Control Plane application: assembles modules, owns HTTP error handling, health and DB migrations.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":shared:kernel"))
    implementation(project(":control-plane:identity"))
    implementation(project(":control-plane:catalog"))
    implementation(project(":control-plane:platform"))
    implementation(project(":control-plane:evidence"))
    implementation(project(":control-plane:submission"))
    implementation(project(":control-plane:lab"))
    implementation(project(":control-plane:ctf"))
    implementation(project(":control-plane:response"))
    implementation(project(":control-plane:insight"))
    implementation(project(":execution:fake-worker"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(testFixtures(project(":content:format")))
    testImplementation(project(":execution:agent"))
    testImplementation(project(":lab-gateway"))
    testImplementation(libs.spring.boot.starter.security.oauth2.client)
    testImplementation(libs.mock.oauth2.server)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.rabbitmq)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The learner workspace (web/, D-06) is built with npm; when its output exists it is served from /app/.
// Without a build the API still works and /app/ is not available.
tasks.processResources {
    from(rootProject.file("web/dist")) { into("static/app") }
}
