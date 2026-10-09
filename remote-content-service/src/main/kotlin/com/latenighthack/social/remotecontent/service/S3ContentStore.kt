package com.latenighthack.social.remotecontent.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.amazon.awssdk.auth.credentials.*
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import java.net.URI
import java.security.MessageDigest
import java.time.Duration

/** Existing /content URLs remain stable. Storage keys mirror the file-store layout. */
class S3ContentStore(private val client: S3Client, private val bucket: String, private val prefix: String = "content/") : ContentStore, AutoCloseable {
    private fun key(id: ByteArray): String {
        require(id.isNotEmpty() && id.size <= 128)
        val hex = id.joinToString("") { "%02x".format(it.toInt() and 255) }
        return prefix + hex.take(2) + "/" + hex.drop(2).ifEmpty { hex }
    }
    override suspend fun create(id: ByteArray, mimeType: String?) { withContext(Dispatchers.IO) {
        client.putObject(PutObjectRequest.builder().bucket(bucket).key(key(id) + ".mime").contentType("text/plain").build(), RequestBody.fromString(mimeType.orEmpty()))
    } }
    private fun read(key: String): software.amazon.awssdk.core.ResponseBytes<GetObjectResponse>? = try {
        client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build())
    } catch (missing: S3Exception) { if (missing.statusCode() == 404) null else throw missing }
    override suspend fun put(id: ByteArray, bytes: ByteArray) { withContext(Dispatchers.IO) {
        val key = key(id)
        val mime = read(key + ".mime")?.asUtf8String()?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(mime)
            .metadata(mapOf("sha256" to sha256(bytes))).build(), RequestBody.fromBytes(bytes))
    } }
    override suspend fun get(id: ByteArray): StoredContent? = withContext(Dispatchers.IO) {
        val response = read(key(id)) ?: return@withContext null
        val bytes = response.asByteArray()
        val expected = response.response().metadata()["sha256"]
        check(expected == null || expected == sha256(bytes)) { "Content checksum mismatch" }
        StoredContent(bytes, response.response().contentType())
    }
    override fun close() { client.close() }
    companion object {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun fromEnv(env: Map<String, String> = System.getenv()): S3ContentStore {
            fun required(key: String) = requireNotNull(env[key]?.takeIf { it.isNotBlank() }) { "Missing $key" }
            val client = S3Client.builder().endpointOverride(URI(required("CONTENT_S3_ENDPOINT")))
                .region(Region.of(env["CONTENT_S3_REGION"] ?: "auto"))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(required("CONTENT_S3_ACCESS_KEY_ID"), required("CONTENT_S3_SECRET_ACCESS_KEY"))))
                .overrideConfiguration { it.apiCallTimeout(Duration.ofSeconds(15)).apiCallAttemptTimeout(Duration.ofSeconds(5)) }
                .build()
            return S3ContentStore(client, required("CONTENT_S3_BUCKET"))
        }
    }
}
