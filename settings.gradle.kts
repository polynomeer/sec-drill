rootProject.name = "secdrill"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

// Module layout follows SecDrill-docs/docs/30-implementation-plan.md; modules are added when they have content.
include(":shared:kernel")
include(":control-plane:identity")
include(":control-plane:app")
