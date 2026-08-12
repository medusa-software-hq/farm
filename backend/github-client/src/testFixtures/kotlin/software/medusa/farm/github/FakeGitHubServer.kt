package software.medusa.farm.github

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A tiny in-test stand-in for `api.github.com`: binds an ephemeral loopback port, records each
 * request, and answers via the supplied [handler]. Point a client's `baseUrl` at [baseUrl].
 */
class FakeGitHubServer(private val handler: (Request) -> Response) : AutoCloseable {
  class Request(val method: String, val pathAndQuery: String, val authorization: String?)

  class Response(
      val status: Int,
      val body: String,
      val headers: Map<String, String> = emptyMap(),
  )

  val requests = CopyOnWriteArrayList<Request>()

  private val server =
      HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
          val request =
              Request(
                  exchange.requestMethod,
                  exchange.requestURI.toString(),
                  exchange.requestHeaders.getFirst("Authorization"),
              )
          requests += request
          exchange.requestBody.readBytes()
          val response =
              try {
                handler(request)
              } catch (e: Throwable) {
                Response(500, e.message ?: "handler error")
              }
          val bytes = response.body.toByteArray()
          response.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
          exchange.sendResponseHeaders(response.status, bytes.size.toLong())
          exchange.responseBody.use { it.write(bytes) }
        }
        start()
      }

  val baseUrl: String
    get() = "http://127.0.0.1:${server.address.port}"

  override fun close() = server.stop(0)
}
