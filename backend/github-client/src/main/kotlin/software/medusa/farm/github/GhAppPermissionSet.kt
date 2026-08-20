package software.medusa.farm.github

/** The permissions an App holds, or the ones something needs it to hold. */
data class GhAppPermissionSet(val modeById: Map<GhPermissionId, GhPermissionMode>) {
  /** Reads as the permission lines of a GitHub App's settings page, for putting in a message. */
  fun describe(): String =
      modeById.entries
          .sortedBy { it.key.wireValue }
          .joinToString(", ") { "${it.key.wireValue}: ${it.value.wireValue}" }

  sealed interface CoverageResult {
    data object Covered : CoverageResult

    /**
     * [missingPermissionSet] is what was asked for and not held — at the mode it was asked for,
     * which is the mode to grant, rather than whatever lesser one is held now.
     */
    data class MissingPermissions(val missingPermissionSet: GhAppPermissionSet) : CoverageResult
  }

  companion object {
    fun checkCoverage(
        requiredPermissionSet: GhAppPermissionSet,
        actualPermissionSet: GhAppPermissionSet,
    ): CoverageResult {
      val missingModeById =
          requiredPermissionSet.modeById.filterNot { (id, requiredMode) ->
            actualPermissionSet.modeById[id]?.covers(requiredMode) == true
          }

      return if (missingModeById.isEmpty()) CoverageResult.Covered
      else CoverageResult.MissingPermissions(GhAppPermissionSet(missingModeById))
    }
  }
}
