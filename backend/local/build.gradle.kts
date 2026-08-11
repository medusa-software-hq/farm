plugins {
  alias(libs.plugins.kotlin.jvm)

  application
}

dependencies {
  implementation(project(":backend:api:core"))
  implementation(project(":backend:worker:core"))
  implementation(project(":backend:shared"))
  runtimeOnly(libs.logback.classic)
}

application { mainClass = "software.medusa.farm.local.MainKt" }
