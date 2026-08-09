plugins {
  alias(libs.plugins.kotlin.jvm)

  application
}

dependencies {
  implementation(project(":backend:shared"))
  implementation(libs.kotlinx.coroutines.core)
  runtimeOnly(libs.postgresql)
  runtimeOnly(libs.logback.classic)

  testImplementation(libs.kotlin.test)
}

kotlin { jvmToolchain(21) }

application { mainClass = "software.medusa.farm.worker.MainKt" }
