package com.latenighthack.social.remotecontent.domain

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.latenighthack.ktbuf.net.RpcClient
import com.latenighthack.ktbuf.net.RpcMethodSpecifier
import com.latenighthack.ktbuf.net.RpcResponse
import com.latenighthack.ktbuf.net.RpcServerStream
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals

class RemoteContentTransferTest {

    // createContent is never called in these tests (we PUT/GET a fixed URL directly), so the rpc stub
    // is never exercised.
    private object FakeRpc : RpcClient {
        override suspend fun unaryCall(
            method: RpcMethodSpecifier,
            headers: Map<String, String>,
            request: ByteArray,
        ): RpcResponse = error("unused")

        override suspend fun serverStreamingCall(
            method: RpcMethodSpecifier,
            block: suspend RpcServerStream.() -> Unit,
            readyCallback: () -> Unit,
        ) = error("unused")
    }

    @Test(timeout = 60_000)
    fun `upload and download are watched separately and each reaches completion`() = runBlocking {
        val stored = ConcurrentHashMap<String, ByteArray>()
        val server = embeddedServer(ServerCIO, port = 0) {
            routing {
                put("/content/{id}") {
                    stored[call.parameters["id"]!!] = call.receive<ByteArray>()
                    call.respond(HttpStatusCode.OK)
                }
                get("/content/{id}") {
                    val bytes = stored[call.parameters["id"]!!]
                    if (bytes == null) call.respond(HttpStatusCode.NotFound) else call.respondBytes(bytes)
                }
            }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val http = HttpClient(ClientCIO)
        val observations = java.util.concurrent.CopyOnWriteArrayList<Triple<String, String, Double>>()
        val client = RemoteContentClientImpl(FakeRpc, http, com.latenighthack.social.runtime.OperationsObserver { stage, outcome, seconds, _, _ ->
            observations.add(Triple(stage, outcome, seconds))
            if (stage == "content_first_byte") error("A failed observer must not break the content request")
        })
        val url = "http://localhost:$port/content/abc"
        val bytes = ByteArray(64 * 1024) { it.toByte() }

        // Nothing tracked in either direction before any transfer.
        assertThat(client.watchUpload(url).first()).isNull()
        assertThat(client.watchDownload(url).first()).isNull()

        client.upload(url, bytes)
        val uploadProgress = client.watchUpload(url).first()
        assertThat(uploadProgress).isNotNull()
        assertThat(uploadProgress!!.totalBytes).isEqualTo(bytes.size.toLong())
        assertThat(uploadProgress.bytesTransferred).isEqualTo(uploadProgress.totalBytes)
        // The upload does not show up as a download.
        assertThat(client.watchDownload(url).first()).isNull()

        val downloaded = client.download(url)
        assertContentEquals(bytes, downloaded.bytes)
        val downloadProgress = client.watchDownload(url).first()
        assertThat(downloadProgress).isNotNull()
        assertThat(downloadProgress!!.bytesTransferred).isEqualTo(downloadProgress.totalBytes)
        val firstByte = observations.single { it.first == "content_first_byte" }
        val completion = observations.single { it.first == "content_download" }
        kotlin.test.assertTrue(firstByte.third <= completion.third)
        kotlin.test.assertEquals("success", completion.second)
        kotlin.test.assertFailsWith<IllegalStateException> { client.download("http://localhost:$port/content/missing") }
        kotlin.test.assertEquals("failure", observations.last().second)

        http.close()
        server.stop()
    }
}
