plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  `java-library`
  `java-test-fixtures`
}

// Drives the `claude` CLI as a subprocess: builds its stream-json invocation, parses the NDJSON
// event stream, and snapshots/restores session state so a run can be resumed later.
// A pure connector — no Temporal, no GitHub, no DB.
dependencies {
  api(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  // The subprocess plumbing (spawn/stream/kill-tree) lives in commons; this module adapts it.
  implementation(libs.medusa.commons.system)

  testImplementation(libs.kotlin.test)
}

base { archivesName = "backend-claude-connector" }

// Integration tests drive the REAL `claude` CLI to validate the assumptions the connector bakes in
// about its behavior. They live in a separate source set kept out of the `test`/`check` lifecycle
// and run via the `integrationTest` task; each test skips itself when its prerequisites (the
// binary,
// and for the paid behavioral checks a CLAUDE_CODE_OAUTH_TOKEN) are absent.
val integrationTest by sourceSets.creating {
  compileClasspath += sourceSets["main"].output
  runtimeClasspath += sourceSets["main"].output
}

configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])

configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

tasks.register<Test>("integrationTest") {
  description = "Runs integration tests against the real claude CLI."
  group = "verification"
  testClassesDirs = integrationTest.output.classesDirs
  classpath = integrationTest.runtimeClasspath
  useJUnitPlatform()
  shouldRunAfter(tasks.named("test"))
  // Probes the real CLI (and an env-provided token), neither of which is a task input, so never
  // treat this as up to date — always re-run when invoked.
  outputs.upToDateWhen { false }
  // Log each test's outcome (with full failure detail) so a CI run makes plain whether the
  // behavioral checks ran, skipped, or why they failed.
  testLogging {
    events("passed", "skipped", "failed")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}
