package software.medusa.farm.cli.auth

/** Raised when there's no usable session — the caller turns it into a "run ms-farm login" hint. */
class NotLoggedInException(message: String) : Exception(message)
