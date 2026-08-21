package software.medusa.farm.github

/** A repository the app can reach, together with the issues open on it. */
class GhRepoWithOpenIssues(
    val repo: GhRepo,
    val openIssues: List<GhIssue>,
)
