package software.medusa.farm.github

/** A repository's full name, `owner/name`. */
@JvmInline
value class GhRepoFullName(val value: String) {
  val owner: GhOrgLogin
    get() = GhOrgLogin(value.substringBefore('/'))
}
