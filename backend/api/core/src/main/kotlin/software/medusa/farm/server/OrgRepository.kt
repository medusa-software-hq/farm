package software.medusa.farm.server

import software.medusa.farm.github.GhIssue
import software.medusa.farm.github.GhOrgLogin
import software.medusa.farm.github.GhRepoFullName

/** A repository the Farm app can reach, tagged with its org and carrying a few recent issues. */
class OrgRepository(
    val orgLogin: GhOrgLogin,
    val fullName: GhRepoFullName,
    val recentIssues: List<GhIssue>,
)
