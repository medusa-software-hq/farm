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
