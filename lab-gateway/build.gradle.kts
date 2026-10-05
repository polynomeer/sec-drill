plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    api(project(":shared:kernel"))
    implementation(libs.jackson.databind)
}
