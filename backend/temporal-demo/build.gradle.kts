plugins {
  alias(libs.plugins.kotlin.jvm)

  application
}

dependencies {
  implementation(libs.temporal.sdk)
  implementation(libs.temporal.kotlin)
  runtimeOnly(libs.logback.classic)
}

kotlin { jvmToolchain(21) }

application {
  // Arg-less, env-configured, like the worker: reads TEMPORAL_ADDRESS / TEMPORAL_NAMESPACE /
  // TEMPORAL_API_KEY from the environment. Run with `./gradlew :backend:temporal-demo:run`.
  mainClass = "software.medusa.farm.temporaldemo.MainKt"
}
