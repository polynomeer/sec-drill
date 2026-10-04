import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
}

// Modules that run outside the Control Plane trust boundary (11, 17) or are shared with it.
// They must never reach the Control DB: no JDBC, driver, pool, migration or ORM artifacts,
// and no dependency on a Control Plane module.
val boundedPrefixes = listOf(":execution:", ":shared:")
val forbiddenModules = listOf(
    "org.postgresql:",
    "com.zaxxer:HikariCP",
    "org.springframework:spring-jdbc",
    "org.springframework:spring-orm",
    "org.springframework.boot:spring-boot-jdbc",
    "org.springframework.boot:spring-boot-starter-jdbc",
    "org.springframework.boot:spring-boot-starter-data-",
    "org.springframework.data:",
    "org.flywaydb:",
    "org.liquibase:",
    "org.jooq:",
    "org.hibernate",
    "jakarta.persistence:",
)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(21)
            compilerOptions {
                freeCompilerArgs.addAll("-Xjsr305=strict")
            }
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            systemProperty("secdrill.contracts.dir", rootProject.file("SecDrill-docs/contracts").absolutePath)
            inputs.dir(rootProject.file("SecDrill-docs/contracts")).withPropertyName("contracts")
        }

        if (boundedPrefixes.any { path.startsWith(it) }) {
            val projectPath = path
            val roots = listOf("runtimeClasspath", "compileClasspath").map { name ->
                configurations.named(name).flatMap { it.incoming.resolutionResult.rootComponent }
            }
            val boundaryCheck = tasks.register("checkControlDbBoundary") {
                group = "verification"
                description = "Fails if this module can reach Control DB libraries or Control Plane modules."
                inputs.property("forbidden", forbiddenModules)
                doLast {
                    val violations = sortedSetOf<String>()
                    roots.forEach { root ->
                        val seen = mutableSetOf<ResolvedComponentResult>()
                        fun visit(component: ResolvedComponentResult) {
                            if (!seen.add(component)) return
                            when (val id = component.id) {
                                is ModuleComponentIdentifier -> {
                                    val coordinates = "${id.group}:${id.module}"
                                    if (forbiddenModules.any { coordinates.startsWith(it) }) violations += coordinates
                                }
                                is ProjectComponentIdentifier ->
                                    if (id.projectPath.startsWith(":control-plane:")) violations += id.projectPath
                            }
                            component.dependencies.forEach { dependency ->
                                when (dependency) {
                                    is ResolvedDependencyResult -> visit(dependency.selected)
                                    // An unresolvable request (e.g. missing version) still declares intent; judge it by coordinates.
                                    is UnresolvedDependencyResult -> (dependency.requested as? ModuleComponentSelector)?.let {
                                        val coordinates = "${it.group}:${it.module}"
                                        if (forbiddenModules.any { prefix -> coordinates.startsWith(prefix) }) violations += coordinates
                                    }
                                }
                            }
                        }
                        visit(root.get())
                    }
                    check(violations.isEmpty()) {
                        "$projectPath must not depend on Control DB or Control Plane modules: ${violations.joinToString()}"
                    }
                }
            }
            tasks.named("check") { dependsOn(boundaryCheck) }
        }
    }
}
