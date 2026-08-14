plugins {
  alias(libs.plugins.kotlin.jvm)
  `java-library`
}

dependencies {
  implementation(project(":backend:shared"))
  implementation(project(":backend:github-client"))
  implementation(project(":backend:claude-connector"))
  // `api`: WorkerConfig.commitAuthor is a GitCliAuthor, so consumers (runner, local) see the type.
  api(project(":backend:git-cli"))
  // Locating and spawning the claude binary the connector drives.
  implementation(libs.medusa.commons.system)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.temporal.sdk)
  // Lets Temporal's Jackson converter (de)serialize the Kotlin types crossing the activity
  // boundary.
  implementation(libs.jackson.module.kotlin)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.temporal.testing)
  testImplementation(testFixtures(project(":backend:github-client")))
}

kotlin { jvmToolchain(21) }

// Distinct jar name so the proper/runner distributions don't collide with other modules' jars.
base { archivesName = "backend-worker-core" }
