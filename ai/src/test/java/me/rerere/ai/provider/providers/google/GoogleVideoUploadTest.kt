package me.rerere.ai.provider.providers.google

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.blackholeSink
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

class GoogleVideoUploadTest {
    @get:Rule val temp = TemporaryFolder()
    private val setting = ProviderSetting.Google(apiKey = "test-key")
    private val seen = mutableListOf<Request>()
    private var timestamp = 1_800_000_000_000L
    private var state = "ACTIVE"
    private var cacheStatus = 200
    private var failUpload = false
    private var uploadedBytes = 0L

    private fun resource() = """{"name":"files/video123","uri":"https://generativelanguage.googleapis.com/v1beta/files/video123","state":"$state","expirationTime":"${java.time.Instant.ofEpochMilli(timestamp + 48 * 60 * 60_000L)}"}"""

    private fun uploader(): GoogleVideoUpload {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            seen.add(req)
            var code = 200
            var body = "{}"
            val result = Response.Builder().request(req).protocol(Protocol.HTTP_1_1).message("Test")
            when {
                req.header("X-Goog-Upload-Command") == "start" ->
                    result.header("X-Goog-Upload-URL", "https://upload.example.test/session?upload_id=secret")
                req.header("X-Goog-Upload-Command") == "upload, finalize" -> {
                    // Consume the complete 72 MiB through a bounded sink, never a full Buffer/byte array.
                    blackholeSink().buffer().use { req.body!!.writeTo(it) }
                    uploadedBytes = req.body!!.contentLength()
                    if (failUpload) code = 500
                    body = """{"file":${resource()}}"""
                }
                req.url.encodedPath.contains("/files/") -> {
                    code = cacheStatus
                    if (state == "PROCESSING") state = "ACTIVE"
                    body = resource()
                }
                req.url.host == "storage.googleapis.com" -> {
                    if (req.method == "POST") {
                        blackholeSink().buffer().use { req.body!!.writeTo(it) }
                        uploadedBytes = req.body!!.contentLength()
                    }
                }
                else -> error("Unexpected request")
            }
            result.code(code).body(body.toResponseBody("application/json".toMediaType())).build()
        }).build()
        return GoogleVideoUpload(client, temp.root.resolve("cache"), { "test-access-token" }, { timestamp }, 0)
    }

    private fun message(bytes: Long, extension: String = "mp4"): UIMessage {
        val file = temp.newFile("video-${System.nanoTime()}.$extension")
        RandomAccessFile(file, "rw").use { it.setLength(bytes) }
        return UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Video(file.toURI().toString())))
    }

    @Test fun `72 MiB video streams to Files API and request contains only file URI`() = runBlocking {
        val original = message(72L * 1024 * 1024, "webm")
        uploader().withPreparedVideos(setting, listOf(original)) { prepared ->
            val part = prepared.single().parts.single() as UIMessagePart.Video
            val json = part.toGoogleVideoPart()
            assertFalse(json.containsKey("inlineData"))
            assertEquals("video/webm", json["fileData"]!!.jsonObject["mimeType"]!!.jsonPrimitive.content)
            assertTrue(part.url.startsWith("https://"))
            assertTrue((original.parts.single() as UIMessagePart.Video).url.startsWith("file:"))
        }
        assertEquals(72L * 1024 * 1024, uploadedBytes)
        assertEquals("/upload/v1beta/files", seen.first().url.encodedPath)
        assertEquals("test-key", seen.first().header("x-goog-api-key"))
        assertNull(seen[1].header("x-goog-api-key"))
    }

    @Test fun `20 MiB stays inline without uploading`() = runBlocking {
        val original = message(INLINE_VIDEO_LIMIT)
        uploader().withPreparedVideos(setting, listOf(original)) { assertEquals(listOf(original), it) }
        assertTrue(seen.isEmpty())
    }

    @Test fun `multiple small videos use upload when their total exceeds threshold`() = runBlocking {
        val messages = listOf(message(11L * 1024 * 1024), message(11L * 1024 * 1024))
        uploader().withPreparedVideos(setting, messages) { prepared ->
            assertTrue(prepared.all { (it.parts.single() as UIMessagePart.Video).url.startsWith("https://") })
        }
        assertEquals(1, seen.count { it.header("X-Goog-Upload-Command") == "start" })
    }

    @Test fun `processing video is polled until ACTIVE before generation`() = runBlocking {
        state = "PROCESSING"
        uploader().withPreparedVideos(setting, listOf(message(INLINE_VIDEO_LIMIT + 1))) {
            assertEquals("ACTIVE", state)
            assertTrue(seen.any { request -> request.method == "GET" })
        }
    }

    @Test fun `cache survives uploader recreation and is validated before reuse`() = runBlocking {
        val original = message(INLINE_VIDEO_LIMIT + 1)
        uploader().withPreparedVideos(setting, listOf(original)) { }
        seen.clear()
        uploader().withPreparedVideos(setting, listOf(original)) { }
        assertEquals(1, seen.size)
        assertEquals("GET", seen.single().method)
    }

    @Test fun `expired or server deleted files are uploaded again`() = runBlocking {
        val original = message(INLINE_VIDEO_LIMIT + 1)
        val uploader = uploader()
        uploader.withPreparedVideos(setting, listOf(original)) { }
        cacheStatus = 404
        uploader.withPreparedVideos(setting, listOf(original)) { }
        assertEquals(2, seen.count { it.header("X-Goog-Upload-Command") == "start" })
        timestamp += 49 * 60 * 60_000L
        cacheStatus = 200
        uploader.withPreparedVideos(setting, listOf(original)) { }
        assertEquals(3, seen.count { it.header("X-Goog-Upload-Command") == "start" })
    }

    @Test fun `API keys do not share file cache`() = runBlocking {
        val original = message(INLINE_VIDEO_LIMIT + 1)
        val uploader = uploader()
        uploader.withPreparedVideos(setting, listOf(original)) { }
        uploader.withPreparedVideos(setting.copy(apiKey = "another-key"), listOf(original)) { }
        assertEquals(2, seen.count { it.header("X-Goog-Upload-Command") == "start" })
    }

    @Test fun `upload failure propagates without inline fallback`() = runBlocking {
        failUpload = true
        try {
            uploader().withPreparedVideos(setting, listOf(message(INLINE_VIDEO_LIMIT + 1))) { fail("Must not generate") }
            fail("Must report upload failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("HTTP 500"))
        }
    }

    @Test fun `processing failure does not send an unusable file to Gemini`() = runBlocking {
        state = "FAILED"
        try {
            uploader().withPreparedVideos(setting, listOf(message(INLINE_VIDEO_LIMIT + 1))) { fail("Must not generate") }
            fail("Must report processing failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("processing failed"))
        }
    }

    @Test fun `Vertex requires explicit GCS configuration`() = runBlocking {
        try {
            uploader().withPreparedVideos(setting.copy(vertexAI = true), listOf(message(INLINE_VIDEO_LIMIT + 1))) { fail() }
            fail("Must require GCS")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("GCS bucket"))
        }
        assertTrue(seen.isEmpty())
    }

    @Test fun `Vertex streams to GCS and deletes temporary object even when generation fails`() = runBlocking {
        val vertex = setting.copy(vertexAI = true, useServiceAccount = true, videoStorageBucket = "test-bucket")
        try {
            uploader().withPreparedVideos(vertex, listOf(message(72L * 1024 * 1024))) { prepared ->
                assertTrue((prepared.single().parts.single() as UIMessagePart.Video).url.startsWith("gs://test-bucket/"))
                error("Generation failed")
            }
            fail("Must propagate generation failure")
        } catch (e: IllegalStateException) {
            assertEquals("Generation failed", e.message)
        }
        assertEquals(72L * 1024 * 1024, uploadedBytes)
        assertEquals(listOf("POST", "DELETE"), seen.map { it.method })
        assertTrue(seen.all { it.header("Authorization") == "Bearer test-access-token" })
    }

    @Test fun `Vertex removes temporary upload when generation is cancelled`() = runBlocking {
        val vertex = setting.copy(vertexAI = true, useServiceAccount = true, videoStorageBucket = "test-bucket")
        try {
            uploader().withPreparedVideos(vertex, listOf(message(INLINE_VIDEO_LIMIT + 1))) {
                kotlinx.coroutines.withTimeout(10) { kotlinx.coroutines.delay(10_000) }
            }
            fail("Must be cancelled")
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            assertEquals(listOf("POST", "DELETE"), seen.map { it.method })
        }
    }

    @Test fun `Interactions sends uploaded videos as URI without Base64`() {
        val messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(
            UIMessagePart.Video("https://generativelanguage.googleapis.com/v1beta/files/video123")
        )))
        val input = InteractionsAPI(OkHttpClient()).buildInput(messages).toString()
        assertTrue(input.contains("\"uri\""))
        assertFalse(input.contains("\"data\""))
    }
}
