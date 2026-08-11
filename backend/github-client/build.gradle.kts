plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  `java-library`
  `java-test-fixtures`
}

// A leaf GitHub API client. No proto, no gRPC, no DB. HTTP is the JDK client so this adds no new
// HTTP stack to the build.
dependencies {
  api(libs.kotlinx.coroutines.core)
  api(libs.nimbus.jose.jwt)
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.kotlin.test)
}

base { archivesName = "backend-github-client" }
