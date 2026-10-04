// Job protocol between the Control Plane and execution workers (16, 20). Data and interfaces only; no DB, no Spring.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":shared:kernel"))
}
