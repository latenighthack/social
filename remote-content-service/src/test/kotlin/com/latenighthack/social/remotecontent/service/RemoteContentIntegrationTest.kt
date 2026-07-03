package com.latenighthack.social.remotecontent.service

import com.latenighthack.ktbuf.server.serveAll
import com.latenighthack.ktbuf.test.server.runTestWithServer
import com.latenighthack.lockers.server.rpcClient
import com.latenighthack.social.remotecontent.v1.CreateContentRequest
import com.latenighthack.social.remotecontent.v1.RemoteContentServer
import com.latenighthack.social.remotecontent.v1.RemoteContentServiceRpc
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Boots just the remote-content service (RemoteContent gRPC + /content HTTP routes)
// over a fresh in-memory store. URLs use a blank base, so they are root-relative and
// the test reaches them by prefixing the embedded server's URL.
suspend fun Application.attachRemoteContent() {
    val store = InMemoryContentStore()
    val service = RemoteContentServiceImpl(store, ContentUrls(""))
    routing {
        serveAll(service, RemoteContentServer.Descriptor)
        remoteContent(store)
    }
}

class RemoteContentIntegrationTest {

    @Test
    fun `create, upload, then download round-trips the bytes and mime type`() =
        runTestWithServer(Application::attachRemoteContent) { server, _ ->
            val rpc = RemoteContentServiceRpc(server.rpcClient)
            val base = server.serverUrl.trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
            HttpClient(CIO).use { http ->
                val created = rpc.createContent(CreateContentRequest { mimeType = "image/png" })

                val id = assertNotNull(created.contentId)
                assertTrue(id.rawValue.isNotEmpty())
                assertTrue(created.uploadUrl.startsWith(ContentUrls.CONTENT_PATH))
                assertTrue(created.downloadUrl.startsWith(ContentUrls.CONTENT_PATH))

                // Reserved but not yet uploaded → not found.
                assertEquals(HttpStatusCode.NotFound, http.get(base + created.downloadUrl).status)

                val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)
                val put = http.put(base + created.uploadUrl) { setBody(bytes) }
                assertEquals(HttpStatusCode.OK, put.status)

                val download = http.get(base + created.downloadUrl)
                assertEquals(HttpStatusCode.OK, download.status)
                assertTrue(bytes.contentEquals(download.body<ByteArray>()))
                assertEquals("image/png", download.contentType()?.toString())
            }
        }

    @Test
    fun `content created without a mime type downloads as octet-stream`() =
        runTestWithServer(Application::attachRemoteContent) { server, _ ->
            val rpc = RemoteContentServiceRpc(server.rpcClient)
            val base = server.serverUrl.trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
            HttpClient(CIO).use { http ->
                val created = rpc.createContent(CreateContentRequest { })

                val bytes = byteArrayOf(42)
                http.put(base + created.uploadUrl) { setBody(bytes) }

                val download = http.get(base + created.downloadUrl)
                assertEquals(HttpStatusCode.OK, download.status)
                assertTrue(bytes.contentEquals(download.body<ByteArray>()))
                assertEquals("application/octet-stream", download.contentType()?.toString())
            }
        }
}
