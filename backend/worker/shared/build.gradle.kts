plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
  implementation(project(":backend:shared"))
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.temporal.sdk)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.temporal.testing)
}

kotlin { jvmToolchain(21) }

// Distinct jar name so the proper/runner distributions don't collide with :backend:shared's
// shared.jar (both projects are named "shared").
base { archivesName = "backend-worker-shared" }
