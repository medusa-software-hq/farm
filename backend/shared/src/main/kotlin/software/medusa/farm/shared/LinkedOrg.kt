package software.medusa.farm.shared

/** A GitHub org linked to the Farm app, keyed by the app's installation id in that org. */
class LinkedOrg(
    val installationId: Long,
    val orgLogin: String,
)
