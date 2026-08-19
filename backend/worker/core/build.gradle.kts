plugins {
  alias(libs.plugins.kotlin.jvm)
  `java-library`
}

dependencies {
  implementation(project(":backend:shared"))
  implementation(project(":backend:github-client"))
  implementation(project(":backend:claude-connector"))
  // `api`: WorkerConfig.commitAuthor is a GitCliAuthor, so consumers (runner, local) see the type.
  api(project(":backend:git-cli"))
  // Locating and spawning the claude binary the connector drives.
  implementation(libs.medusa.commons.system)
  // Summarizing a run: a cheap model (DeepSeek over OpenRouter) via the commons openai-client.
  implementation(libs.medusa.commons.openaiClient)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.temporal.sdk)
  // Lets Temporal's Jackson converter (de)serialize the Kotlin types crossing the activity
  // boundary.
  implementation(libs.jackson.module.kotlin)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.temporal.testing)
  testImplementation(testFixtures(project(":backend:github-client")))
}

kotlin { jvmToolchain(21) }

// The summarizer's integration test hits the REAL model, which costs money. Kept out of the
// `test`/`check` lifecycle, so it runs only when asked for — and then it is required: a missing
// OPENROUTER_API_KEY fails it rather than skipping it into a green no-op.
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
    // The failure itself says only that no summary could be had; the reason is logged. Surface the
    // streams so a red CI run carries it, rather than leaving it in a log nobody reads.
    showStandardStreams = true
  }
}

// Distinct jar name so the proper/runner distributions don't collide with other modules' jars.
base { archivesName = "backend-worker-core" }
