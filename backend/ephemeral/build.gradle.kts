plugins {
  alias(libs.plugins.kotlin.jvm)

  application
}

dependencies {
  implementation(project(":backend:api:core"))
  implementation(project(":backend:worker:core"))
  implementation(project(":backend:shared"))
  implementation(project(":backend:github-client"))
  implementation(libs.kotlinx.coroutines.core)
  runtimeOnly(libs.logback.classic)
}

application { mainClass = "software.medusa.farm.ephemeral.MainKt" }
