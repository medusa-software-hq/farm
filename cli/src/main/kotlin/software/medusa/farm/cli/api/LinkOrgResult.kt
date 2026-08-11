package software.medusa.farm.cli.api

/** The outcome of linking an org: the app's installation id there and the repos it can reach. */
class LinkOrgResult(
    val installationId: Long,
    val repositories: List<String>,
)
