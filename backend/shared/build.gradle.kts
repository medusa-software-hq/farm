plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.sqldelight)
  `java-library`
}

dependencies {
  // api() (not implementation) so consumers get Hikari's transitive slf4j-api at compile time.
  api(libs.hikaricp)
  api(libs.kotlinx.coroutines.core)
  implementation(libs.flyway.core)
  implementation(libs.sqldelight.jdbc.driver)
  runtimeOnly(libs.flyway.database.postgresql)
  runtimeOnly(libs.postgresql)

  testImplementation(libs.kotlin.test)
}

sqldelight {
  databases {
    create("FarmDatabase") {
      packageName.set("software.medusa.farm.shared.db")
      dialect(libs.sqldelight.postgresql.dialect)
    }
  }
}

base { archivesName = "backend-shared" }
