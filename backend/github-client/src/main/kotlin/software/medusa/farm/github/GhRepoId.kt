package software.medusa.farm.github

/** GitHub's stable numeric repository id: survives renames, unlike the full name. */
@JvmInline value class GhRepoId(val value: Long)
