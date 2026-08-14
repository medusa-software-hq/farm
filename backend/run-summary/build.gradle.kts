plugins { alias(libs.plugins.kotlin.jvm) }

// Summarizes what a coding-agent run did — its action log distilled to a short, dense note — via a
// cheap model (DeepSeek over OpenRouter), using the commons openai-client. A follow-up fixup run
// gets that note as orientation instead of the raw, sparse log; the agent re-reads the repo anyway.
// A pure seam — no Temporal, no GitHub, no DB.
dependencies {
  api(libs.kotlinx.coroutines.core)
  // The action-log model summarize() takes; a String is only used internally to prompt the model.
  api(project(":backend:shared"))
  implementation(libs.medusa.commons.openaiClient)

  testImplementation(libs.kotlin.test)
}

base { archivesName = "backend-run-summary" }

// Integration tests hit the REAL model (DeepSeek over OpenRouter) to validate the wiring. Kept out
// of the `test`/`check` lifecycle; each skips itself when OPENROUTER_API_KEY is absent.
val integrationTest by sourceSets.creating {
  compileClasspath += sourceSets["main"].output
  runtimeClasspath += sourceSets["main"].output
}

configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])

configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

tasks.register<Test>("integrationTest") {
  description = "Runs integration tests against the real model (DeepSeek over OpenRouter)."
  group = "verification"
  testClassesDirs = integrationTest.output.classesDirs
  classpath = integrationTest.runtimeClasspath
  useJUnitPlatform()
  shouldRunAfter(tasks.named("test"))
  // Probes an env-provided API key, not a task input, so never treat this as up to date.
  outputs.upToDateWhen { false }
  testLogging {
    events("passed", "skipped", "failed")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}
