plugins {
  alias(libs.plugins.jib)
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.sqldelight)

  application
}

val javaVersion = 21
val containerImageRef = findProperty("jib.imageRef")?.toString() ?: "farm-worker"
val containerImageTag = findProperty("jib.imageTag")?.toString() ?: "local"

dependencies {
  implementation(libs.temporal.sdk)
  implementation(libs.temporal.kotlin)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.hikaricp)
  implementation(libs.sqldelight.jdbc.driver)
  implementation(libs.flyway.core)
  runtimeOnly(libs.flyway.database.postgresql)
  runtimeOnly(libs.postgresql)
  runtimeOnly(libs.logback.classic)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.temporal.testing)
}

application {
  // Arg-less, env-configured entrypoint. Not a CLI: it reads all config from the
  // environment, connects to Temporal, and runs a long-lived worker process.
  mainClass = "software.medusa.farm.worker.MainKt"
}

sqldelight {
  databases {
    create("FarmWorkerDatabase") {
      packageName.set("software.medusa.farm.worker.db")
      dialect(libs.sqldelight.postgresql.dialect)
    }
  }
}

jib {
  from { image = "eclipse-temurin:$javaVersion-jre-alpine" }

  to {
    image = containerImageRef
    tags = setOf(containerImageTag)
  }

  container { mainClass = "software.medusa.farm.worker.MainKt" }
}
