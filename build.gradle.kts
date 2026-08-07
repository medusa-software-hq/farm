plugins {
  alias(libs.plugins.jib) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.protobuf) apply false
  alias(libs.plugins.shadow) apply false
  alias(libs.plugins.sqldelight) apply false
  alias(libs.plugins.versionCatalogUpdate)
  alias(libs.plugins.ktfmt) apply false
  alias(libs.plugins.detekt) apply false
}

val kotlinJvmPluginId = libs.plugins.kotlin.jvm.get().pluginId
val ktfmtPluginId = libs.plugins.ktfmt.get().pluginId
val detektPluginId = libs.plugins.detekt.get().pluginId

// Java 21 is the current broadly adopted LTS
val usedJavaVersion = 21

allprojects {
  repositories {
    // Virtually all modules need Maven Central dependencies
    mavenCentral()
  }
}

subprojects {
  // Configure Kotlin/JVM modules.
  pluginManager.withPlugin(kotlinJvmPluginId) {
    // Apply Kotlin formatting and static analysis plugins.
    pluginManager.apply(ktfmtPluginId)
    pluginManager.apply(detektPluginId)

    tasks.named("check") {
      // Run formatting checks as part of the standard verification lifecycle.
      dependsOn(tasks.named("ktfmtCheck"))
    }

    extensions.configure<JavaPluginExtension> {
      toolchain {
        // Use a consistent Java toolchain version across local and CI builds.
        languageVersion = JavaLanguageVersion.of(usedJavaVersion)
      }
    }

    tasks.withType<JavaCompile>().configureEach {
      // Preserve parameter names in bytecode for runtime reflection.
      options.compilerArgs.add("-parameters")
    }
  }

  tasks.withType<Test>().configureEach { useJUnitPlatform() }
}
