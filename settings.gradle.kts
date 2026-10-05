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
include(":content:format")
include(":content:cli")
include(":execution:protocol")
include(":execution:fake-worker")
include(":execution:agent")
include(":lab-gateway")
include(":control-plane:identity")
include(":control-plane:catalog")
include(":control-plane:platform")
include(":control-plane:ctf")
include(":control-plane:evidence")
include(":control-plane:submission")
include(":control-plane:lab")
include(":control-plane:app")
