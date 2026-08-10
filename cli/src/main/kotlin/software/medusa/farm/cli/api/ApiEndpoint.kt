package software.medusa.farm.cli.api

/** Where a FarmService lives: host + port, and whether to dial it over TLS. */
data class ApiEndpoint(val host: String, val port: Int, val useTls: Boolean)
