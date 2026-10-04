// Platform plumbing shared by Control Plane modules: Outbox writer and publisher, consumer inbox,
// Idempotency-Key store and a fault-injection seam for crash-point tests (14, 15, 16, ADR 0004).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    api(project(":shared:kernel"))
    implementation(libs.spring.boot.starter.jdbc)
    api(libs.spring.boot.starter.amqp)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
}
