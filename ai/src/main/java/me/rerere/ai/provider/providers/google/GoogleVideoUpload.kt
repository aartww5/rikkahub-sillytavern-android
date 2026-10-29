package me.rerere.ai.provider.providers.google

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomHeaders
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.resumeWithException

internal const val INLINE_VIDEO_LIMIT = 20L * 1024 * 1024
private const val VIDEO_MIME_METADATA = "googleVideoMimeType"

internal fun localVideoFile(url: String): File? =
    if (url.startsWith("file:")) File(URI(url)) else null

internal fun videoMimeType(url: String): String = when (
    url.substringBefore('?').substringAfterLast('.').lowercase()
) {
    "webm" -> "video/webm"
    "mov" -> "video/quicktime"
    "avi" -> "video/x-msvideo"
    "mpeg", "mpg" -> "video/mpeg"
    "3gp" -> "video/3gpp"
    "flv" -> "video/x-flv"
    "wmv" -> "video/x-ms-wmv"
    else -> "video/mp4"
}

internal fun UIMessagePart.Video.googleVideoMimeType(): String =
    metadata?.get(VIDEO_MIME_METADATA)?.jsonPrimitive?.contentOrNull ?: videoMimeType(url)

internal fun UIMessagePart.Video.toGoogleVideoPart(): JsonObject = buildJsonObject {
    if (localVideoFile(url) != null) {
        check(localVideoFile(url)!!.length() <= INLINE_VIDEO_LIMIT) {
            "Large videos must be uploaded before building the request."
        }
        put("inlineData", buildJsonObject {
            put("mimeType", googleVideoMimeType())
            put("data", encodeBase64(false).getOrThrow())
        })
    } else {
        require(url.startsWith("https://") || url.startsWith("gs://")) {
            "Unsupported video URI. Select a local video or use an HTTPS / gs:// URI."
        }
        put("fileData", buildJsonObject {
            put("mimeType", googleVideoMimeType())
            put("fileUri", url)
        })
    }
}

/** Upload only request copies: saved chat attachments always keep their local URI. */
internal class GoogleVideoUpload(
    client: OkHttpClient,
    private val cacheDir: File? = null,
    private val accessToken: suspend (ProviderSetting.Google) -> String,
    private val now: () -> Long = System::currentTimeMillis,
    private val pollIntervalMs: Long = 2_000,
) {
    // Upload session URLs carry credentials. Retain proxy/network settings, omit raw HTTP logging.
    private val client = client.newBuilder().apply {
        interceptors().removeAll { it is HttpLoggingInterceptor }
        networkInterceptors().removeAll { it is HttpLoggingInterceptor }
    }.build()
    private val mutex = Mutex()
    private val memoryCache = mutableMapOf<String, JsonObject>()

    suspend fun <T> withPreparedVideos(
        setting: ProviderSetting.Google,
        messages: List<UIMessage>,
        block: suspend (List<UIMessage>) -> T,
    ): T {
        val videos = mutableListOf<UIMessagePart.Video>()
        fun collect(parts: List<UIMessagePart>) {
            parts.forEach {
                when (it) {
                    is UIMessagePart.Video -> videos.add(it)
                    is UIMessagePart.Tool -> collect(it.output)
                    else -> Unit
                }
            }
        }
        messages.forEach { collect(it.parts) }
        val files = videos.mapNotNull { video -> localVideoFile(video.url)?.let { video.url to it } }.toMap()
        files.values.forEach { require(it.isFile && it.length() > 0) { "Video file is missing or empty." } }
        // Also protect requests with several individually small videos.
        val upload = videos.sumOf { localVideoFile(it.url)?.length() ?: 0L } > INLINE_VIDEO_LIMIT
        if (!upload) return block(messages)

        val cleanup = mutableListOf<suspend () -> Unit>()
        try {
            val replacements = mutableMapOf<String, String>()
            for ((url, file) in files) {
                replacements[url] = if (setting.vertexAI) {
                    uploadGcs(setting, file, cleanup)
                } else {
                    uploadGemini(setting, file)
                }
            }
            fun replace(parts: List<UIMessagePart>): List<UIMessagePart> = parts.map { part ->
                when (part) {
                    is UIMessagePart.Video -> replacements[part.url]?.let { uri ->
                        part.copy(
                            url = uri,
                            metadata = JsonObject(part.metadata.orEmpty() +
                                (VIDEO_MIME_METADATA to JsonPrimitive(videoMimeType(part.url)))),
                        )
                    } ?: part
                    is UIMessagePart.Tool -> part.copy(output = replace(part.output))
                    else -> part
                }
            }
            return block(messages.map { it.copy(parts = replace(it.parts)) })
        } finally {
            // Close/cancel the generation before removing the temporary GCS objects.
            withContext(NonCancellable + Dispatchers.IO) {
                cleanup.forEach { remove ->
                    try {
                        withTimeout(15_000) { remove() }
                    } catch (_: Exception) {
                        // A bucket lifecycle rule is the fallback when the process is killed/offline.
                    }
                }
            }
        }
    }

    private suspend fun uploadGemini(setting: ProviderSetting.Google, file: File): String = mutex.withLock {
        val base = setting.baseUrl.trimEnd('/').toHttpUrl()
        require(base.encodedPath.trimEnd('/').endsWith("/v1beta") ||
            base.encodedPath.trimEnd('/').endsWith("/v1")) {
            "Large video uploads require a Gemini base URL ending in /v1beta or /v1."
        }
        val cacheKey = sha256((setting.id.toString() + base + setting.apiKey + hashFile(file)).toByteArray())
        val cacheFile = cacheDir?.let { File(it, "$cacheKey.json") }
        val cached = memoryCache[cacheKey] ?: cacheFile?.takeIf { it.isFile }?.let {
            runCatching { json.parseToJsonElement(it.readText()).jsonObject }.getOrNull()
        }
        if (cached != null && expiresAt(cached) > now() + 60_000) {
            val get = geminiRequest(setting, "$base/${cached.requiredString("name")}".toHttpUrl()).get().build()
            execute(get).use { response ->
                if (response.isSuccessful) {
                    val resource = readObject(response)
                    if (resource.string("state") == "ACTIVE") return@withLock resource.requiredString("uri")
                } else if (response.code != 404 && response.code != 403) {
                    checkResponse(response)
                }
            }
        }
        memoryCache.remove(cacheKey)
        cacheFile?.delete()
        val mime = videoMimeType(file.name)
        val uploadUrl = base.newBuilder()
            .encodedPath(base.encodedPath.trimEnd('/').removeSuffix("/v1beta").removeSuffix("/v1") +
                "/upload/" + base.pathSegments.last { it.isNotEmpty() } + "/files")
            .build()
        val start = geminiRequest(setting, uploadUrl)
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", file.length().toString())
            .header("X-Goog-Upload-Header-Content-Type", mime)
            .post("{\"file\":{\"display_name\":\"RikkaHub video\"}}".toRequestBody("application/json".toMediaType()))
            .build()
        val session = execute(start).use { response ->
            checkResponse(response)
            response.header("X-Goog-Upload-URL")?.toHttpUrl() ?: error("Files API did not return an upload URL.")
        }
        // The session URL is a bearer capability; never attach our API key/custom headers to it.
        require(session.isHttps || !base.isHttps) { "Files API returned an insecure upload URL." }
        val uploadRequest = Request.Builder().url(session)
            .header("X-Goog-Upload-Command", "upload, finalize")
            .header("X-Goog-Upload-Offset", "0")
            .post(file.asRequestBody(mime.toMediaType())).build()
        var resource = execute(uploadRequest).use { response ->
            checkResponse(response)
            readObject(response)["file"]?.jsonObject ?: error("Files API response is missing file metadata.")
        }
        withTimeout(10 * 60_000L) {
            while (resource.string("state") != "ACTIVE") {
                check(resource.string("state") == "PROCESSING") { "Video processing failed. Try a different video format." }
                delay(pollIntervalMs)
                val get = geminiRequest(setting, "$base/${resource.requiredString("name")}".toHttpUrl()).get().build()
                resource = execute(get).use { response -> checkResponse(response); readObject(response) }
            }
        }
        val uri = resource.requiredString("uri")
        memoryCache[cacheKey] = resource
        if (memoryCache.size > 64) memoryCache.remove(memoryCache.keys.first())
        cacheDir?.let { dir ->
            runCatching {
                dir.mkdirs()
                dir.listFiles()?.filter { it.extension == "json" && now() - it.lastModified() > 48 * 60 * 60_000L }
                    ?.forEach { it.delete() }
                cacheFile!!.writeText(resource.toString())
            }
        }
        uri
    }

    private suspend fun uploadGcs(
        setting: ProviderSetting.Google,
        file: File,
        cleanup: MutableList<suspend () -> Unit>,
    ): String {
        require(setting.useServiceAccount && setting.videoStorageBucket.isNotBlank()) {
            "Vertex large videos require a service account and a GCS bucket in provider settings. " +
                "Gemini Files API resources cannot be used by Vertex."
        }
        val bucket = setting.videoStorageBucket.trim().removePrefix("gs://").trimEnd('/')
        require(bucket.matches(Regex("[a-z0-9][a-z0-9._-]{1,220}[a-z0-9]"))) { "Enter a GCS bucket name without a path." }
        val token = accessToken(setting)
        val objectName = "rikkahub-video/${UUID.randomUUID()}/${file.name}"
        val uploadUrl = "https://storage.googleapis.com/upload/storage/v1/b/".toHttpUrl().newBuilder()
            .addPathSegment(bucket).addPathSegment("o")
            .addQueryParameter("uploadType", "media").addQueryParameter("name", objectName)
            .addQueryParameter("ifGenerationMatch", "0").build()
        val deleteUrl = "https://storage.googleapis.com/storage/v1/b/".toHttpUrl().newBuilder()
            .addPathSegment(bucket).addPathSegment("o").addPathSegment(objectName).build()
        // Register before sending: a cancelled upload might have reached GCS already.
        cleanup.add {
            execute(Request.Builder().url(deleteUrl).header("Authorization", "Bearer ${accessToken(setting)}")
                .delete().build()).use { response ->
                if (response.code != 404) checkResponse(response)
            }
        }
        execute(Request.Builder().url(uploadUrl).header("Authorization", "Bearer $token")
            .post(file.asRequestBody(videoMimeType(file.name).toMediaType())).build()).use { checkResponse(it) }
        return "gs://$bucket/$objectName"
    }

    private fun geminiRequest(setting: ProviderSetting.Google, url: HttpUrl) = Request.Builder()
        .url(url).headers(setting.mergeCustomHeaders()).header("x-goog-api-key", setting.apiKey)

    private fun expiresAt(resource: JsonObject): Long = runCatching {
        Instant.parse(resource.requiredString("expirationTime")).toEpochMilli()
    }.getOrDefault(0)

    private suspend fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun readObject(response: Response) = json.parseToJsonElement(response.body.string()).jsonObject

    private fun checkResponse(response: Response) {
        check(response.isSuccessful) {
            "Video upload failed (HTTP ${response.code}). Check Files API support, credentials, quota, or GCS permissions."
        }
    }

    // Unlike the shared await helper, cancel the actual HTTP call when generation is stopped.
    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }
}

private fun JsonObject.string(key: String) = get(key)?.jsonPrimitive?.contentOrNull
private fun JsonObject.requiredString(key: String) = string(key)?.takeIf { it.isNotBlank() }
    ?: error("Video upload response is missing $key.")
