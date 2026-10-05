import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
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
val controlDbModules = listOf(
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

/** What a module may not reach: external module prefixes and in-build project paths. */
data class Boundary(val modules: List<String>, val projects: (String) -> Boolean, val reason: String)

fun boundaryFor(path: String): Boundary? = when {
    // Authoring tools run on author machines and CI; they never reach the Control DB either.
    path.startsWith(":execution:") || path.startsWith(":shared:") || path.startsWith(":content:") || path == ":lab-gateway" ->
        Boundary(controlDbModules, { it.startsWith(":control-plane:") }, "Control DB or Control Plane modules")
    // Domain modules cooperate through application services and events (11); only the app assembles them.
    path.startsWith(":control-plane:") && path != ":control-plane:app" ->
        Boundary(emptyList(), { it == ":control-plane:app" }, "the assembling :control-plane:app module")
    else -> null
}

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

        val boundary = boundaryFor(path) ?: return@withId
        val projectPath = path
        val roots = listOf("runtimeClasspath", "compileClasspath").map { name ->
            configurations.named(name).flatMap { it.incoming.resolutionResult.rootComponent }
        }
        val boundaryCheck = tasks.register("checkModuleBoundary") {
            group = "verification"
            description = "Fails if this module can reach ${boundary.reason}."
            inputs.property("forbiddenModules", boundary.modules)
            doLast {
                val violations = sortedSetOf<String>()
                fun forbiddenModule(coordinates: String) = boundary.modules.any { coordinates.startsWith(it) }
                roots.forEach { root ->
                    val seen = mutableSetOf<ResolvedComponentResult>()
                    fun visit(component: ResolvedComponentResult) {
                        if (!seen.add(component)) return
                        when (val id = component.id) {
                            is ModuleComponentIdentifier ->
                                "${id.group}:${id.module}".let { if (forbiddenModule(it)) violations += it }
                            is ProjectComponentIdentifier ->
                                if (id.projectPath != projectPath && boundary.projects(id.projectPath)) violations += id.projectPath
                        }
                        component.dependencies.forEach { dependency ->
                            when (dependency) {
                                is ResolvedDependencyResult -> visit(dependency.selected)
                                // An unresolvable request (e.g. missing version) still declares intent; judge it by coordinates.
                                is UnresolvedDependencyResult -> (dependency.requested as? ModuleComponentSelector)
                                    ?.let { "${it.group}:${it.module}" }
                                    ?.let { if (forbiddenModule(it)) violations += it }
                            }
                        }
                    }
                    visit(root.get())
                }
                check(violations.isEmpty()) {
                    "$projectPath must not depend on ${boundary.reason}: ${violations.joinToString()}"
                }
            }
        }
        tasks.named("check") { dependsOn(boundaryCheck) }
    }
}
