plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
  implementation(project(":backend:shared"))
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.temporal.sdk)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.temporal.testing)
}

kotlin { jvmToolchain(21) }

// Distinct jar name so the proper/runner distributions don't collide with other modules' jars.
base { archivesName = "backend-worker-core" }
