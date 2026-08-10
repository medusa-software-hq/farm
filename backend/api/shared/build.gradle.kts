import com.google.protobuf.gradle.id

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.protobuf)
  `java-library`
}

// Proto sources live at the repo root, shared across services.
sourceSets { main { proto { srcDir(rootDir.resolve("proto")) } } }

dependencies {
  api(platform(libs.armeria.bom))
  api(platform(libs.grpc.bom))

  api(libs.armeria.grpc)
  api(libs.armeria.grpc.kotlin)
  api(libs.armeria.kotlin)
  api(project(":backend:shared"))
  api(libs.grpc.kotlin.stub)
  api(libs.grpc.protobuf)
  api(libs.grpc.stub)
  api(libs.kotlinx.coroutines.core)
  api(libs.nimbus.jose.jwt)
  api(libs.protobuf.kotlin)
  runtimeOnly(libs.logback.classic)
  runtimeOnly(libs.postgresql)

  testImplementation(libs.kotlin.test)
}

val grpcJavaId = "grpc"
val grpcKotlinId = "grpckt"

protobuf {
  protoc { artifact = "${libs.protobuf.protoc.get()}" }

  plugins {
    id(grpcJavaId) { artifact = "${libs.protobuf.protocGen.grpc.java.get()}" }
    id(grpcKotlinId) { artifact = "${libs.protobuf.protocGen.grpc.kotlin.get()}:jdk8@jar" }
  }

  generateProtoTasks {
    all().forEach { protoTask ->
      protoTask.plugins {
        id(grpcJavaId)
        id(grpcKotlinId)
      }
      protoTask.builtins { id("kotlin") }
    }
  }
}

base { archivesName = "backend-api-shared" }
