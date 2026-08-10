plugins {
  alias(libs.plugins.kotlin.jvm)

  application
}

dependencies {
  implementation(project(":backend:worker:shared"))
  implementation(platform(libs.google.cloud.bom))
  implementation(libs.google.cloud.secretmanager)
  runtimeOnly(libs.postgresql)
  runtimeOnly(libs.logback.classic)
}

kotlin { jvmToolchain(21) }

application { mainClass = "software.medusa.farm.worker.MainKt" }
