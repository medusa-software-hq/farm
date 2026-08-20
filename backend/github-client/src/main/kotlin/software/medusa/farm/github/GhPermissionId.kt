package software.medusa.farm.github

/**
 * A permission a GitHub App can be granted, by the name the API gives it.
 *
 * The ones this library's callers ask for, rather than all of them: an App holding a permission
 * named nowhere here is an App holding a permission nobody checks.
 */
enum class GhPermissionId(val wireValue: String) {
  Actions("actions"),
  Administration("administration"),
  Checks("checks"),
  Contents("contents"),
  Issues("issues"),
  Members("members"),
  Metadata("metadata"),
  PullRequests("pull_requests"),
  Workflows("workflows"),
}
