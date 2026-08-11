package software.medusa.farm.github

/**
 * The credential seam the resource surface authenticates through: any source of a bearer token
 * GitHub accepts. Minting or refreshing one is network I/O, hence [provideToken] suspends.
 */
interface GhTokenProvider {
  suspend fun provideToken(): String
}
