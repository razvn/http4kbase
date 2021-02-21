package net.razvan

import net.razvan.ServerSentEvent.Listener
import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.http4k.lens.Path
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.routing.sse
import org.http4k.server.PolyHandler
import org.http4k.server.Undertow
import org.http4k.server.asServer
import org.http4k.sse.Sse
import org.http4k.sse.SseMessage
import java.time.LocalDateTime
import kotlin.concurrent.thread

val app = routes(
    "/ping" bind GET to {
        Response(OK).body("pong")
    },

    "/testing/kotest" bind GET to { request ->
        Response(OK).body("Echo '${request.bodyString()}'")
    }
)

fun main() {

    val namePath = Path.of("name")

    val sse = sse(
        "/{name}" bind { sse: Sse ->
            val name = namePath(sse.connectRequest)
            sse.send(SseMessage.Data("hello: $name"))
            thread(true) {
                (1..10).forEach {
                    sse.send(SseMessage.Event("count", "$name - ${Thread.currentThread().name}", "$it - ${LocalDateTime.now()}"))
                    Thread.sleep(1000)
                }
            }

            sse.onClose { println("$name is closing") }
        }
    )

    val http = routes("/{name}" bind GET to { req: Request ->
        val cli = KOkSse()
        val name = namePath(req)
        val request = Request(GET, "http://localhost:9000/$name")
        println("calling $name")
        val ssevent = cli.newServerSentEvent(request, SSEListener())
        Response(OK).body("OK")
    })

    val server = PolyHandler(http, sse = sse).asServer(Undertow(9000)).start()
    println("Server started on " + server.port())
}

class SSEListener : Listener {
    override fun onOpen(sse: ServerSentEvent, response: Response?) {
        println("Opened")
    }

    override fun onMessage(sse: ServerSentEvent, id: String?, event: String, message: String) {
        println("Message: id: $id - event: $event - msg: $message")
        if (message.startsWith("10")) {
            println("10, it's time to close")
            sse.close()
        }
    }

    override fun onComment(sse: ServerSentEvent, comment: String) {
        println("New comment")
        println("Comment: $comment")
    }

    override fun onRetryTime(sse: ServerSentEvent, milliseconds: Long): Boolean {
        println("Retry time")
        println("Retry in: $milliseconds ms")
        return true
    }

    override fun onRetryError(sse: ServerSentEvent, throwable: Throwable, response: Response?): Boolean {
        println("Retry error")
        println("Error: $throwable | response: $response")
        return false
    }

    override fun onClosed(sse: ServerSentEvent) {
        println("Close")
    }

    override fun onPreRetry(sse: ServerSentEvent, originalRequest: Request): Request? {
        println("Preretry: $originalRequest")
        return originalRequest
    }
}