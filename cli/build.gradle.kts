plugins {
  application

  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.shadow)
}

dependencies {
  implementation(libs.clikt)
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.kotlin.test)
}

application {
  mainClass = "software.medusa.farm.cli.MainKt"

  // Clikt pulls in JNA (terminal detection); recent JDKs warn on its System.load unless native
  // access is opted in. Keep the installDist launcher quiet (the Homebrew launcher passes the
  // same).
  applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// Bake the per-environment OAuth client secrets into the fat jar as a resource. The Publish CLI
// workflow passes them via `-PcliOauthClientSecret` (prod) / `-PcliStagingOauthClientSecret`
// (staging), from Actions secrets. Absent locally → empty values, and each Environment falls back
// to
// its `oauthClientSecretEnvVar`, so dev builds still work. Backend URLs + client ids are NOT baked
// —
// they're deterministic public source constants on Environment. Neither secret is ever committed.
val cliBuildConfigDir = layout.buildDirectory.dir("generated/cliBuildConfig")

val generateCliBuildConfig by tasks.registering {
  val prodSecret = providers.gradleProperty("cliOauthClientSecret").orElse("")
  val stagingSecret = providers.gradleProperty("cliStagingOauthClientSecret").orElse("")
  inputs.property("prodSecret", prodSecret)
  inputs.property("stagingSecret", stagingSecret)
  outputs.dir(cliBuildConfigDir)
  doLast {
    val file = cliBuildConfigDir.get().file("farm-cli-build.properties").asFile
    file.parentFile.mkdirs()
    // GOCSPX-… secrets are properties-safe. Written by hand to avoid Properties.store's date
    // comment.
    file.writeText(
        "oauthClientSecret=${prodSecret.get()}\nstagingOauthClientSecret=${stagingSecret.get()}\n"
    )
  }
}

sourceSets.named("main") { resources.srcDir(generateCliBuildConfig) }

tasks.shadowJar {
  archiveBaseName = "farm-cli"
  archiveClassifier = ""
  archiveVersion = ""
}
