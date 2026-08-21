import com.google.protobuf.gradle.id

plugins {
  application

  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.protobuf)
  alias(libs.plugins.shadow)
}

// The FarmService proto lives at the repo root, shared with the backend; the CLI generates a
// blocking gRPC stub from the same contract the server implements.
sourceSets { main { proto { srcDir(rootDir.resolve("proto")) } } }

// The per-environment backend host + OAuth client id are generated from the resolved config in
// infra/config — the single source shared with Terraform — so the CLI can't drift from the
// deployed environments (the bug this replaced: hardcoded ids from a different project). Nothing is
// committed; it regenerates whenever the config changes.
val environmentsConfigFile = rootDir.resolve("infra/config/config.json")
val generatedEnvironmentsDir = layout.buildDirectory.dir("generated/environments/kotlin")

val generateEnvironments by tasks.registering {
  inputs.file(environmentsConfigFile)
  outputs.dir(generatedEnvironmentsDir)
  doLast {
    @Suppress("UNCHECKED_CAST")
    val config = groovy.json.JsonSlurper().parse(environmentsConfigFile) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    val environments = config["environments"] as Map<String, Map<String, Any?>>

    fun value(env: String, key: String): String =
        environments[env]?.get(key)?.toString()
            ?: error("config.json is missing environments.$env.$key")

    fun block(property: String, env: String): String =
        """
            |  val $property: Config =
            |      Config(
            |          apiHost = "${value(env, "api_host")}",
            |          oauthClientId = "${value(env, "cli_client_id")}",
            |      )
            """
            .trimMargin()

    val content = buildString {
      appendLine("// Generated from infra/config/config.json — do not edit.")
      appendLine("package software.medusa.farm.cli.config")
      appendLine()
      appendLine("internal object GeneratedEnvironments {")
      appendLine("  data class Config(val apiHost: String, val oauthClientId: String)")
      appendLine()
      appendLine(block("prod", "prod"))
      appendLine()
      appendLine(block("staging", "staging"))
      appendLine("}")
    }

    val packageDir = generatedEnvironmentsDir.get().dir("software/medusa/farm/cli/config").asFile
    packageDir.mkdirs()
    packageDir.resolve("GeneratedEnvironments.kt").writeText(content)
  }
}

kotlin { sourceSets.named("main") { kotlin.srcDir(generateEnvironments) } }

// Generated sources are the codegen's product, not hand-written code to police.
tasks.withType<SourceTask>().configureEach {
  if (name.startsWith("ktfmt") || name.startsWith("detekt")) {
    exclude("**/generated/**")
  }
}

dependencies {
  implementation(libs.clikt)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.nimbus.oauth2.oidc.sdk)

  implementation(platform(libs.grpc.bom))
  implementation(libs.grpc.protobuf)
  implementation(libs.grpc.stub)
  // protoc (4.34.x) generates against a matching protobuf-java; grpc-protobuf otherwise drags in an
  // older 3.x runtime that lacks the generated code's symbols (RuntimeVersion, GeneratedMessage).
  implementation(libs.protobuf.java)
  runtimeOnly(libs.grpc.okhttp)

  testImplementation(libs.kotlin.test)
  // The API server, so the CLI can be asked its questions of a real one: what the service sends
  // back is the half of this that unit tests cannot reach.
  testImplementation(project(":backend:api:core"))
  testImplementation(libs.kotlinx.coroutines.core)
}

val grpcJavaId = "grpc"

protobuf {
  protoc { artifact = "${libs.protobuf.protoc.get()}" }
  plugins { id(grpcJavaId) { artifact = "${libs.protobuf.protocGen.grpc.java.get()}" } }
  generateProtoTasks { all().forEach { it.plugins { id(grpcJavaId) } } }
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
// its `oauthClientSecretEnvVar`, so dev builds still work. Backend hosts + client ids are NOT baked
// —
// they're public values generated from the config. Neither secret is ever committed.
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
  // gRPC discovers its transport/name-resolver/load-balancer providers via META-INF/services;
  // merge those files so the shaded jar keeps a functional channel provider. INCLUDE lets every
  // jar's copy of a given service file reach the merge transformer — otherwise Gradle's duplicate
  // handling drops all but one (e.g. grpc-util's LoadBalancerProvider shadowing grpc-core's
  // pick_first), leaving the channel unable to find its default load-balancer policy at runtime.
  duplicatesStrategy = DuplicatesStrategy.INCLUDE
  mergeServiceFiles()
}
