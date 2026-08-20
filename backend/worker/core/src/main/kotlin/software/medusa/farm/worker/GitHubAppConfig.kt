package software.medusa.farm.worker

import software.medusa.farm.github.GhAppPrivateKey

/** The GitHub App credentials the repo-sync fetch activity needs to mint installation tokens. */
data class GitHubAppConfig(
    val clientId: String,
    val privateKey: GhAppPrivateKey,
)
