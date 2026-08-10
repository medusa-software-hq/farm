package software.medusa.farm.cli.auth

import java.net.URI

/** Opens a URL in the user's browser; returns false if no opener could be launched. */
interface BrowserOpener {
  fun open(url: URI): Boolean
}
