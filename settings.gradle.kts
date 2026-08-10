plugins {
    // Apply the foojay-resolver plugin to allow automatic download of JDKs
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "farm"

include(
    ":backend:shared",
    ":backend:worker:shared",
    ":backend:worker:proper",
    ":backend:worker:runner",
    ":backend:api:proper",
    ":backend:api:local",
    ":backend:api:shared",
    ":cli",
)
