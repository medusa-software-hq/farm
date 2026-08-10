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
  // gRPC discovers its transport/name-resolver/load-balancer providers via META-INF/services;
  // merge those files so the shaded jar keeps a functional channel provider.
  mergeServiceFiles()
}
