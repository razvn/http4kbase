package net.razvan

import org.http4k.client.OkHttp
import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.http4k.core.then
import org.http4k.filter.DebuggingFilters.PrintRequest
import org.http4k.filter.DebuggingFilters.PrintResponse
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.server.ApacheServer
import org.http4k.server.asServer

val app = routes(
    "/ping" bind GET to {
        Response(OK).body("pong")
    },

    "/testing/kotest" bind GET to {request ->
        Response(OK).body("Echo '${request.bodyString()}'")
    }
)

fun main() {

    val server = PrintRequest()
        .then(app)
        .asServer(ApacheServer(9000)).start()

    val client = PrintResponse()
        .then(OkHttp())

    val response = client(Request(GET, "http://localhost:9000/ping"))

    println(response.bodyString())


    println("Server started on " + server.port())
}
