// Evidence Ledger: append-only, per-Session seq and hash chain (10, 14, ADR 0003). Full T09 scope comes later.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    api(project(":shared:kernel"))
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.jackson.module.kotlin)
}
