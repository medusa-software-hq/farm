plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  `java-library`
}

// Drives the `claude` CLI as a subprocess: builds its stream-json invocation, parses the NDJSON
// event stream, and snapshots/restores session state so a run can be resumed later.
// A pure connector — no Temporal, no GitHub, no DB.
dependencies {
  api(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  // The subprocess plumbing (spawn/stream/kill-tree) lives in commons; this module adapts it.
  implementation(libs.medusa.commons.system)

  // Only the integrationTest source set has tests; it inherits these via the extendsFrom below.
  testImplementation(libs.kotlin.test)
}

base { archivesName = "backend-claude-connector" }

// This is a thin driver over a real subprocess, so it is tested by driving a real subprocess — no
// in-JVM fakes. Two kinds of test live in `integrationTest`, both kept out of the `test`/`check`
// lifecycle and run via the `integrationTest` task:
//   - scripted: point the driver at a fake `claude` script (test resources) that speaks the real
//     stream-json format. Free, deterministic, and where the sad paths live (a stream/exit-code
//     mismatch is trivial to script, awkward to provoke on the real binary).
//   - real: drive the actual `claude` binary; each such test skips itself without the binary and a
//     CLAUDE_CODE_OAUTH_TOKEN.
val integrationTest by sourceSets.creating {
  compileClasspath += sourceSets["main"].output
  runtimeClasspath += sourceSets["main"].output
}

// Inherit the main + test deps (kotlin.test → JUnit5, coroutines, commons) rather than re-listing.
configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])

configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

tasks.register<Test>("integrationTest") {
  description =
      "Runs the connector's integration tests (scripted fake binaries + the real claude CLI)."
  group = "verification"
  testClassesDirs = integrationTest.output.classesDirs
  classpath = integrationTest.runtimeClasspath
  useJUnitPlatform()
  // Probes real binaries and an env-provided token, none of which are task inputs — never up to
  // date.
  outputs.upToDateWhen { false }
  testLogging {
    events("passed", "skipped", "failed")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}
