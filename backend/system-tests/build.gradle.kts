plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
  // Only what a system test needs: the API to talk to, the GitHub client to drive with, and the
  // App config type. Nothing of the worker, and nothing that starts a farm.
  implementation(project(":backend:api:core"))
  implementation(project(":backend:github-client"))
  implementation(project(":backend:worker:core"))
  implementation(libs.kotlinx.coroutines.core)

  // Only the integrationTest source set has tests; it inherits these via the extendsFrom below.
  testImplementation(libs.kotlin.test)
  testRuntimeOnly(libs.logback.classic)
}

base { archivesName = "backend-system-tests" }

// The assembled farm driven from outside it, against real GitHub and a real agent. Kept out of the
// `test`/`check` lifecycle because it costs money and needs a farm to point at, and run on demand.
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
  description = "Drives one issue all the way round, against whichever farm it is pointed at."
  group = "verification"
  testClassesDirs = integrationTest.output.classesDirs
  classpath = integrationTest.runtimeClasspath
  useJUnitPlatform()
  // Talks to services that are not task inputs — never up to date.
  outputs.upToDateWhen { false }
  testLogging {
    // The test narrates itself as it goes; without this Gradle would hold that back until the end,
    // which for this one is a quarter of an hour later.
    showStandardStreams = true
    events("passed", "skipped", "failed")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}
