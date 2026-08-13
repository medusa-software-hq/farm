package software.medusa.farm.github

/** An issue on a repository, with its label names. */
data class GhIssue(val number: Int, val title: String, val labels: List<String>)
