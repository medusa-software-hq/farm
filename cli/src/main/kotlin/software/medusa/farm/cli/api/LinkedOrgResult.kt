package software.medusa.farm.cli.api

/** A GitHub org linked to the Farm app: its login and the app's installation id there. */
class LinkedOrgResult(
    val orgLogin: String,
    val installationId: Long,
)
