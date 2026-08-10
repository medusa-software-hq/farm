plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
  implementation(project(":backend:shared"))
  implementation(libs.kotlinx.coroutines.core)

  testImplementation(libs.kotlin.test)
}

kotlin { jvmToolchain(21) }

// Distinct jar name so the proper/runner distributions don't collide with :backend:shared's
// shared.jar (both projects are named "shared").
base { archivesName = "worker-shared" }
