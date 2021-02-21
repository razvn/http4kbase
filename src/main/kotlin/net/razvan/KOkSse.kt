package net.razvan


import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.http.HttpMethod
import okio.BufferedSource
import org.http4k.core.*
import org.http4k.core.Method.GET
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern


class KOkSse(
    private val client: OkHttpClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true).build()
) {
    fun newServerSentEvent(request: Request, listener: ServerSentEvent.Listener): ServerSentEvent {
        val sse = RealServerSentEvent(request, listener)
        sse.connect(client)
        return sse
    }
}

interface ServerSentEvent {
    fun request(): Request?
    fun setTimeout(timeout: Long, unit: TimeUnit)
    fun close()

    interface Listener {
        fun onOpen(sse: ServerSentEvent, response: Response?)
        fun onMessage(sse: ServerSentEvent, id: String?, event: String, message: String)
        fun onComment(sse: ServerSentEvent, comment: String)
        fun onRetryTime(sse: ServerSentEvent, milliseconds: Long): Boolean
        fun onRetryError(sse: ServerSentEvent, throwable: Throwable, response: Response?): Boolean
        fun onClosed(sse: ServerSentEvent)
        fun onPreRetry(sse: ServerSentEvent, originalRequest: Request): Request?
    }
}

class RealServerSentEvent(request: Request, listener: ServerSentEvent.Listener) : ServerSentEvent {
    private val listener: ServerSentEvent.Listener
    private val originalRequest: Request
    private var client: OkHttpClient = OkHttpClient()
    private var call: Call? = null
    private var reconnectTime = TimeUnit.SECONDS.toMillis(3)
    private var readTimeoutMillis: Long = 0
    private var lastEventId: String? = null

    fun connect(client: OkHttpClient) {
        this.client = client
        prepareCall(originalRequest)
        enqueue()
    }

    private fun prepareCall(request: Request) {
        val requestBuilder: okhttp3.Request.Builder = request.asOkHttp().newBuilder()
            .header("Accept-Encoding", "")
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")

        lastEventId?.let {
            requestBuilder.header("Last-Event-Id", it)
        }

        call = client.newCall(requestBuilder.build())
    }

    private fun enqueue() {
        call?.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                notifyFailure(e, null)
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                if (response.isSuccessful) {
                    openSse(response)
                } else {
                    notifyFailure(IOException(response.message), response.asHttp4k(bodyMode = BodyMode.Memory))
                }
            }

        })
    }

    private fun openSse(response: okhttp3.Response) {
        response.body.use { body ->
            if (body != null) {
                val sseReader = Reader(body.source())
                sseReader.setTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
                listener.onOpen(this, response.asHttp4k(BodyMode.Stream))
                while (call?.isCanceled() != true && sseReader.read()) {
                    // wait
                }
            }
        }
    }

    private fun notifyFailure(throwable: Throwable, response: Response?) {
        if (!retry(throwable, response)) {
            listener.onClosed(this)
            close()
        }
    }

    private fun retry(throwable: Throwable, response: Response?): Boolean {
        if (!Thread.currentThread().isInterrupted && call?.isCanceled() == false && listener.onRetryError(
                this,
                throwable,
                response
            )
        ) {
            val request: Request = listener.onPreRetry(this, originalRequest) ?: return false
            prepareCall(request)
            try {
                Thread.sleep(reconnectTime)
            } catch (ignored: InterruptedException) {
                return false
            }
            if (!Thread.currentThread().isInterrupted && call?.isCanceled() == false) {
                enqueue()
                return true
            }
        }
        return false
    }

    override fun request(): Request {
        return originalRequest
    }


    override fun setTimeout(timeout: Long, unit: TimeUnit) {
        readTimeoutMillis = unit.toMillis(timeout)
    }

    override fun close() {
        call?.takeIf { !it.isCanceled() }?.cancel()
    }

    private inner class Reader(private val source: BufferedSource) {
        private val DIGITS_ONLY: Pattern = Pattern.compile("^[\\d]+$")

        private val data = StringBuilder()
        private var eventName = Companion.DEFAULT_EVENT

        fun read(): Boolean {
            try {
                val line = source.readUtf8LineStrict()
                processLine(line)
            } catch (e: IOException) {
                notifyFailure(e, null)
                return false
            }
            return true
        }

        fun setTimeout(timeout: Long, unit: TimeUnit?) {
            source.timeout().timeout(timeout, unit!!)
        }

        private fun processLine(line: String?) {
            //log("Sse read line: " + line);
            if (line == null || line.isEmpty()) { // If the line is empty (a blank line). Dispatch the event.
                dispatchEvent()
                return
            }
            val colonIndex = line.indexOf(Companion.COLON_DIVIDER)
            when {
                colonIndex == 0 -> { // If line starts with COLON dispatch a comment
                    listener.onComment(this@RealServerSentEvent, line.substring(1).trim { it <= ' ' })
                }
                colonIndex != -1 -> { // Collect the characters on the line after the first U+003A COLON character (:), and let value be that string.
                    val field = line.substring(0, colonIndex)
                    var value = Companion.EMPTY_STRING
                    var valueIndex = colonIndex + 1
                    if (valueIndex < line.length) {
                        if (line[valueIndex] == ' ') { // If value starts with a single U+0020 SPACE character, remove it from value.
                            valueIndex++
                        }
                        value = line.substring(valueIndex)
                    }
                    processField(field, value)
                }
                else -> {
                    processField(line, Companion.EMPTY_STRING)
                }
            }
        }

        private fun dispatchEvent() {
            if (data.isEmpty()) {
                return
            }
            var dataString = data.toString()
            if (dataString.endsWith("\n")) {
                dataString = dataString.substring(0, dataString.length - 1)
            }
            listener.onMessage(this@RealServerSentEvent, lastEventId, eventName, dataString)
            data.setLength(0)
            eventName = Companion.DEFAULT_EVENT
        }

        private fun processField(field: String, value: String) {
            when {
                Companion.DATA == field -> {
                    data.append(value).append('\n')
                }
                Companion.ID == field -> {
                    lastEventId = value
                }
                Companion.EVENT == field -> {
                    eventName = value
                }
                Companion.RETRY == field && DIGITS_ONLY.matcher(value).matches() -> {
                    val timeout = value.toLong()
                    if (listener.onRetryTime(this@RealServerSentEvent, timeout)) {
                        reconnectTime = timeout
                    }
                }
            }
        }
    }

    companion object {
        private const val COLON_DIVIDER = ':'
        private const val UTF8_BOM = "\uFEFF"
        private const val DATA = "data"
        private const val ID = "id"
        private const val EVENT = "event"
        private const val RETRY = "retry"
        private const val DEFAULT_EVENT = "message"
        private const val EMPTY_STRING = ""
    }

    init {
        require(request.method == GET) { "Request must be GET: " + request.method }
        originalRequest = request
        this.listener = listener
    }

    private fun Request.asOkHttp(): okhttp3.Request = headers.fold(
        okhttp3.Request.Builder()
            .url(uri.toString())
            .method(method.toString(), requestBody())
    ) { memo, (first, second) ->
        val notNullValue = second ?: ""
        memo.addHeader(first, notNullValue)
    }.build()

    private fun Request.requestBody() =
        if (HttpMethod.permitsRequestBody(method.toString())) body.payload.array().toRequestBody()
        else null

    private fun okhttp3.Response.asHttp4k(bodyMode: BodyMode): Response {
        val init = Response(Status(code, message))
        val headers = headers.toMultimap().flatMap { it.value.map { hValue -> it.key to hValue } }

        return (body?.let { init.body(bodyMode(it.byteStream())) } ?: init).headers(headers)
    }
}