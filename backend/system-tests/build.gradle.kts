plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
  implementation(project(":backend:api:core"))
  implementation(project(":backend:worker:core"))
  implementation(project(":backend:shared"))
  implementation(project(":backend:github-client"))
  implementation(libs.temporal.sdk)
  implementation(libs.kotlinx.coroutines.core)

  // Only the integrationTest source set has tests; it inherits these via the extendsFrom below.
  testImplementation(libs.kotlin.test)
  testRuntimeOnly(libs.logback.classic)
}

base { archivesName = "backend-system-tests" }

// The whole farm against real things — a real database, real GitHub, a real agent — kept out of the
// `test`/`check` lifecycle because it costs money and needs credentials, and run on demand.
val integrationTest by sourceSets.creating {
  compileClasspath += sourceSets["main"].output
  runtimeClasspath += sourceSets["main"].output
}

configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])

configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

// Named rather than typed: the configuration only exists once the source set above is created.
dependencies {
  "integrationTestImplementation"(libs.grpc.okhttp)
  "integrationTestImplementation"(libs.kotlinx.serialization.json)
}

tasks.register<Test>("integrationTest") {
  description = "Drives one issue all the way round the loop against a real GitHub and database."
  group = "verification"
  testClassesDirs = integrationTest.output.classesDirs
  classpath = integrationTest.runtimeClasspath
  useJUnitPlatform()
  // Talks to services that are not task inputs — never up to date.
  outputs.upToDateWhen { false }
  testLogging {
    events("passed", "skipped", "failed")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}
