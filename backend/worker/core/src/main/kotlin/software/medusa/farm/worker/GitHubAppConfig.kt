package software.medusa.farm.worker

/** The GitHub App credentials the repo-sync fetch activity needs to mint installation tokens. */
data class GitHubAppConfig(
    val clientId: String,
    val pem: String,
)
