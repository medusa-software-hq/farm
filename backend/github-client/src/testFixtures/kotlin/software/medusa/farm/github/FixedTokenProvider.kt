package software.medusa.farm.github

/** A token provider that always hands back the same bearer token. */
class FixedTokenProvider(private val token: String) : GhTokenProvider {
  override suspend fun provideToken(): String = token
}
