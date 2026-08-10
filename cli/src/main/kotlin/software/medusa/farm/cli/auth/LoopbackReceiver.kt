package software.medusa.farm.cli.auth

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A one-shot loopback HTTP server that catches Google's OAuth redirect on an ephemeral localhost
 * port — the standard installed-app flow (RFC 8252), no pre-registered redirect URI needed (Google
 * allows any `127.0.0.1:<port>`). [awaitCallback] returns the full redirect URI for the caller to
 * hand to the OAuth SDK to parse.
 */
class LoopbackReceiver : AutoCloseable {
  private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
  private val received = ArrayBlockingQueue<URI>(1)

  val redirectUri: URI = URI.create("http://127.0.0.1:${server.address.port}")

  init {
    server.createContext("/") { exchange ->
      val bytes = LANDING_PAGE.toByteArray(StandardCharsets.UTF_8)
      exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
      received.offer(redirectUri.resolve(exchange.requestURI))
    }
    server.executor = null
    server.start()
  }

  fun awaitCallback(timeout: Duration): URI =
      received.poll(timeout.toMillis(), TimeUnit.MILLISECONDS)
          ?: throw OAuthException("timeout", "Timed out waiting for the browser sign-in.")

  override fun close() = server.stop(0)

  companion object {
    private const val LANDING_PAGE =
        "<html><body style=\"font-family:sans-serif\">Signed in. You can close this tab and " +
            "return to the terminal.</body></html>"
  }
}
