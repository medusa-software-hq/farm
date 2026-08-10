package software.medusa.farm.cli.api

/** A FarmService call that failed in a way worth showing the user a clean message for. */
class ApiException(message: String) : Exception(message)
