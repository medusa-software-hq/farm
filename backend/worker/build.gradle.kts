plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.sqldelight)

  application
}

dependencies {
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.hikaricp)
  implementation(libs.sqldelight.jdbc.driver)
  runtimeOnly(libs.postgresql)
  runtimeOnly(libs.logback.classic)

  testImplementation(libs.kotlin.test)
}

kotlin { jvmToolchain(21) }

application { mainClass = "software.medusa.farm.worker.MainKt" }

sqldelight {
  databases {
    create("FarmWorkerDatabase") {
      packageName.set("software.medusa.farm.worker.db")
      dialect(libs.sqldelight.postgresql.dialect)
    }
  }
}
