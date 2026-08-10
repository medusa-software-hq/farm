package software.medusa.farm.cli.auth

import java.net.URI

/** The platform [BrowserOpener]: hands the URL to the OS default handler. */
object SystemBrowserOpener : BrowserOpener {
  override fun open(url: URI): Boolean {
    val os = System.getProperty("os.name").lowercase()
    val command =
        when {
          "mac" in os || "darwin" in os -> listOf("open", url.toString())
          "win" in os -> listOf("rundll32", "url.dll,FileProtocolHandler", url.toString())
          else -> listOf("xdg-open", url.toString())
        }
    return runCatching { ProcessBuilder(command).inheritIO().start() }.isSuccess
  }
}
