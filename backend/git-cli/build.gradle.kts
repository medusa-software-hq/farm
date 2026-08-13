plugins {
  alias(libs.plugins.kotlin.jvm)
  `java-library`
}

// Drives the `git` CLI for the operations the commons `git` library doesn't cover — clone, branch,
// commit (optionally signed), push. Long-term these belong in commons `git`; for now they live
// here.
dependencies {
  api(libs.kotlinx.coroutines.core)
  // Spawns the git binary (batch: run, collect output, exit).
  implementation(libs.medusa.commons.system)

  testImplementation(libs.kotlin.test)
}

base { archivesName = "backend-git-cli" }
