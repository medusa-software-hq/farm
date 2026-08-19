package software.medusa.farm.ephemeral

import software.medusa.farm.github.GhAppApiClient

/** A running ephemeral farm, for as long as the block it was given to lasts. */
class EphemeralFarmScope(
    /** Where the API ended up listening, which is only known once it has. */
    val apiPort: Int,
    /** The App itself, for resolving the installation the test drives. */
    val appApiClient: GhAppApiClient,
)
