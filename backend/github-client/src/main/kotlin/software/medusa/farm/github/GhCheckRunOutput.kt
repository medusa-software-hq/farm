package software.medusa.farm.github

/**
 * What a check run reported about itself, as GitHub renders it above the annotations. Any of the
 * three may be empty: a check is free to report nothing and let its annotations speak.
 */
data class GhCheckRunOutput(val title: String, val summary: String, val text: String)
