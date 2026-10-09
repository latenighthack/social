package com.latenighthack.social.remotecontent.domain

import com.latenighthack.ktbuf.net.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.writeFully
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class HttpFailureRegressionTest {
    private object UnusedRpc : RpcClient {
        override suspend fun unaryCall(method: RpcMethodSpecifier, headers: Map<String,String>, request: ByteArray): RpcResponse = error("unused")
        override suspend fun serverStreamingCall(method: RpcMethodSpecifier, block: suspend RpcServerStream.() -> Unit, readyCallback: () -> Unit) = error("unused")
    }
    @Test fun unknownLengthDownloadCannotGrowBeyondTheContentLimit(): Unit = runBlocking {
        val server = embeddedServer(ServerCIO, port = 0) {
            routing { get("/large") {
                call.respondBytesWriter {
                    val chunk = ByteArray(8192)
                    repeat(4096) { writeFully(chunk) }
                }
            } }
        }.start(false)
        val http = HttpClient(ClientCIO)
        try {
            val client = RemoteContentClientImpl(UnusedRpc, http)
            val url = "http://localhost:${server.engine.resolvedConnectors().first().port}/large"
            assertFailsWith<IllegalArgumentException> { client.download(url) }
        } finally { http.close(); server.stop(0, 1000) }
    }

    @Test fun failedPutMustThrow(): Unit = runBlocking {
        val server = embeddedServer(ServerCIO, port=0) { routing { put("/failed") { call.respond(HttpStatusCode.InternalServerError) } } }.start(false)
        val http = HttpClient(ClientCIO)
        try {
            val client = RemoteContentClientImpl(UnusedRpc, http)
            val port = server.engine.resolvedConnectors().first().port
            val failure = runCatching { client.upload("http://localhost:$port/failed", byteArrayOf(1,2,3)) }.exceptionOrNull()
            assertNotNull(failure, "HTTP 500 was accepted as successful upload")
        } finally { http.close(); server.stop() }
    }
    @Test fun missingDownloadMustThrow(): Unit = runBlocking {
        val server = embeddedServer(ServerCIO, port=0) { routing { get("/missing") { call.respond(HttpStatusCode.NotFound) } } }.start(false)
        val http = HttpClient(ClientCIO)
        try {
            val client = RemoteContentClientImpl(UnusedRpc, http)
            val port = server.engine.resolvedConnectors().first().port
            val failure = runCatching { client.download("http://localhost:$port/missing") }.exceptionOrNull()
            assertNotNull(failure, "HTTP 404 was accepted as downloaded content")
        } finally { http.close(); server.stop() }
    }
}
