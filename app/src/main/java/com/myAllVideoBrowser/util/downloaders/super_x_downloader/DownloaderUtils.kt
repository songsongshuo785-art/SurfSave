package com.myAllVideoBrowser.util.downloaders.super_x_downloader

import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.hls_parser.HlsPlaylistParser
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil

object DownloaderUtils {

    private const val AES_128_METHOD = "AES-128"
    private const val AES_128_KEY_BYTES = 16L
    private const val HLS_CAPTURE_MANIFEST = "hls_capture.json"
    private const val HLS_CAPTURE_VERSION = 1

    private data class HlsCaptureManifest(
        val generation: String,
        val videoFileName: String?,
        val audioFileName: String?
    )

    /**
     * This is the MERGE logic, now living in a central utility location.
     * With progress reporting.
     *
     * This version uses executeAsync and blocks until completion to fit the synchronous
     * nature of the calling HlsLiveDownloader.
     */
    fun mergeHlsSegments(
        hlsTmpDir: File,
        videoSegments: List<HlsPlaylistParser.MediaSegment>?,
        audioSegments: List<HlsPlaylistParser.MediaSegment>?,
        finalOutputPath: String,
        videoCodec: String?,
        onMergeProgress: ((percentage: Int) -> Unit)? = null,
        shouldAbort: () -> Boolean = { false }
    ): FFmpegSession {
        throwIfAbortRequested(shouldAbort)
        val arguments = mutableListOf<String>()

        val videoUrlSegments = videoSegments?.let { requireUrlMediaSegments(it, "video") }
        val audioUrlSegments = audioSegments?.let { requireUrlMediaSegments(it, "audio") }
        val isVideoFmp4 = !videoUrlSegments.isNullOrEmpty() &&
            videoUrlSegments.first().initializationSegment != null
        val isAudioFmp4 = !audioUrlSegments.isNullOrEmpty() &&
            audioUrlSegments.first().initializationSegment != null
        val isVideoEncrypted = videoUrlSegments?.any { it.encryptionKey != null } == true
        val isAudioEncrypted = audioUrlSegments?.any { it.encryptionKey != null } == true

        // --- Video Input ---
        if (!videoUrlSegments.isNullOrEmpty()) {
            if (isVideoFmp4 && !isVideoEncrypted) {
                val concatenatedVideoFile = createConcatenatedFmp4File(
                    hlsTmpDir,
                    videoUrlSegments,
                    "video",
                    shouldAbort
                )
                arguments.add("-i")
                arguments.add(concatenatedVideoFile.absolutePath)
            } else {
                val videoPlaylistFile = createLocalHlsPlaylistFile(
                    hlsTmpDir,
                    videoUrlSegments,
                    "segment_",
                    "video.m3u8",
                    segmentExtension = if (isVideoFmp4) "m4s" else "ts",
                    initializationFileName = if (isVideoFmp4) "init_video.mp4" else null,
                    shouldAbort = shouldAbort
                )
                addPlaylistArguments(arguments, videoPlaylistFile)
            }
        }

        // --- Audio Input ---
        if (!audioUrlSegments.isNullOrEmpty()) {
            if (isAudioFmp4 && !isAudioEncrypted) {
                val concatenatedAudioFile = createConcatenatedFmp4File(
                    hlsTmpDir,
                    audioUrlSegments,
                    "audio",
                    shouldAbort
                )
                arguments.add("-i")
                arguments.add(concatenatedAudioFile.absolutePath)
            } else {
                val audioPlaylistFile = createLocalHlsPlaylistFile(
                    hlsTmpDir,
                    audioUrlSegments,
                    "audio_segment_",
                    "audio.m3u8",
                    segmentExtension = if (isAudioFmp4) "m4s" else "ts",
                    initializationFileName = if (isAudioFmp4) "init_audio.mp4" else null,
                    shouldAbort = shouldAbort
                )
                addPlaylistArguments(arguments, audioPlaylistFile)
            }
        }

        if (videoSegments.isNullOrEmpty() && audioSegments.isNullOrEmpty()) {
            throw IOException("Cannot merge segments: No video or audio segments were provided.")
        }

        // --- Calculate Total Duration for Progress ---
        val totalDurationSeconds = (videoSegments?.sumOf { it.duration } ?: 0.0) +
                (audioSegments?.takeIf { videoSegments.isNullOrEmpty() }?.sumOf { it.duration }
                    ?: 0.0)

        addHlsOutputArguments(
            arguments = arguments,
            hasVideo = !videoSegments.isNullOrEmpty(),
            hasAudio = !audioSegments.isNullOrEmpty(),
            videoCodec = videoCodec,
            finalOutputPath = finalOutputPath
        )
        return executeHlsMerge(arguments, totalDurationSeconds, onMergeProgress, shouldAbort)
    }

    fun mergePreparedHlsCapture(
        hlsTmpDir: File,
        finalOutputPath: String,
        videoCodec: String?,
        onMergeProgress: ((percentage: Int) -> Unit)? = null,
        shouldAbort: () -> Boolean = { false }
    ): FFmpegSession {
        throwIfAbortRequested(shouldAbort)
        val videoInput = preparedHlsInputFile(hlsTmpDir, "video")
        val audioInput = preparedHlsInputFile(hlsTmpDir, "audio")
        if (videoInput == null && audioInput == null) {
            throw IOException("No complete HLS capture snapshot is available to merge.")
        }

        val arguments = mutableListOf<String>()
        videoInput?.let { addPlaylistArguments(arguments, it) }
        audioInput?.let { addPlaylistArguments(arguments, it) }
        addHlsOutputArguments(
            arguments = arguments,
            hasVideo = videoInput != null,
            hasAudio = audioInput != null,
            videoCodec = videoCodec,
            finalOutputPath = finalOutputPath
        )
        return executeHlsMerge(arguments, 0.0, onMergeProgress, shouldAbort)
    }

    internal fun publishHlsCaptureSnapshot(
        hlsTmpDir: File,
        videoSegments: List<HlsPlaylistParser.MediaSegment>,
        audioSegments: List<HlsPlaylistParser.MediaSegment>,
        shouldAbort: () -> Boolean = { false }
    ) {
        if (videoSegments.isEmpty() && audioSegments.isEmpty()) {
            throw IOException("Cannot publish an empty HLS capture snapshot.")
        }
        throwIfAbortRequested(shouldAbort)
        val previousManifest = readHlsCaptureManifest(hlsTmpDir)
        val generation = UUID.randomUUID().toString()
        val createdInputs = mutableListOf<File>()

        try {
            val videoInput = createCaptureInput(
                hlsTmpDir = hlsTmpDir,
                segments = videoSegments,
                streamName = "video",
                filePrefix = "segment_",
                generation = generation,
                shouldAbort = shouldAbort
            )?.also(createdInputs::add)
            val audioInput = createCaptureInput(
                hlsTmpDir = hlsTmpDir,
                segments = audioSegments,
                streamName = "audio",
                filePrefix = "audio_segment_",
                generation = generation,
                shouldAbort = shouldAbort
            )?.also(createdInputs::add)
            throwIfAbortRequested(shouldAbort)

            val manifestContent = JSONObject()
                .put("version", HLS_CAPTURE_VERSION)
                .put("generation", generation)
                .put("video", videoInput?.name ?: JSONObject.NULL)
                .put("audio", audioInput?.name ?: JSONObject.NULL)
                .toString()
            publishUtf8Atomically(hlsTmpDir.resolve(HLS_CAPTURE_MANIFEST), manifestContent)

            val activeNames = createdInputs.mapTo(mutableSetOf()) { it.name }
            previousManifest?.let { previous ->
                listOfNotNull(previous.videoFileName, previous.audioFileName)
                    .filterNot(activeNames::contains)
                    .forEach { obsoleteName ->
                        val obsolete = hlsTmpDir.resolve(obsoleteName)
                        if (obsolete.exists() && !obsolete.delete()) {
                            AppLogger.w("HLS: Unable to delete obsolete capture input ${obsolete.name}.")
                        }
                    }
            }
        } catch (error: Exception) {
            createdInputs.forEach { created ->
                if (created.exists() && !created.delete()) {
                    error.addSuppressed(
                        IOException("Unable to remove uncommitted HLS capture input: ${created.absolutePath}")
                    )
                }
            }
            throw error
        }
    }

    internal fun prepareHlsEncryptionKeys(
        httpClient: OkHttpClient,
        hlsTmpDir: File,
        headers: Headers,
        vararg segmentGroups: List<HlsPlaylistParser.MediaSegment>?,
        shouldAbort: () -> Boolean = { false }
    ) {
        val keys = segmentGroups.asSequence()
            .filterNotNull()
            .flatMap { it.asSequence() }
            .map { segment ->
                (segment as? HlsPlaylistParser.UrlMediaSegment)
                    ?: throw IOException("HLS playlist contains an unsupported media segment.")
            }
            .mapNotNull { it.encryptionKey }
            .onEach(::validateEncryptionKey)
            .distinctBy { it.uri }
            .toList()

        keys.forEach { key ->
            throwIfAbortRequested(shouldAbort)
            val keyFile = hlsTmpDir.resolve(encryptionKeyFileName(key.uri))
            if (keyFile.isFile && keyFile.length() == AES_128_KEY_BYTES) {
                return@forEach
            }
            if (keyFile.exists() && !keyFile.delete()) {
                throw IOException("Unable to replace invalid HLS key file: ${keyFile.absolutePath}")
            }
            downloadKey(httpClient, key, keyFile, headers, shouldAbort)
            throwIfAbortRequested(shouldAbort)
        }
    }

    internal fun encryptionKeyFileName(uri: String): String {
        require(uri.isNotBlank()) { "HLS encryption key URI cannot be blank." }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(uri.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }
        return "hls_key_$digest.key"
    }

    @Throws(IOException::class)
    private fun downloadKey(
        httpClient: OkHttpClient,
        key: HlsPlaylistParser.HlsEncryptionKey,
        keyFile: File,
        headers: Headers,
        shouldAbort: () -> Boolean
    ) {
        val temporaryFile = File(keyFile.parentFile, keyFile.name + ".download")
        try {
            if (temporaryFile.exists() && !temporaryFile.delete()) {
                throw IOException("Unable to clear stale HLS key staging file.")
            }
            val request = Request.Builder().url(key.uri).headers(headers).build()
            throwIfAbortRequested(shouldAbort)
            httpClient.newCall(request).execute()
                .use { response ->
                    if (!response.isSuccessful) throw IOException("Failed to download key file. HTTP ${response.code}")
                    val bytes = response.body.byteStream().use { input ->
                        val keyBytes = ArrayList<Byte>((AES_128_KEY_BYTES + 1).toInt())
                        while (keyBytes.size <= AES_128_KEY_BYTES) {
                            throwIfAbortRequested(shouldAbort)
                            val next = input.read()
                            if (next < 0) break
                            keyBytes.add(next.toByte())
                        }
                        keyBytes.toByteArray()
                    }
                    if (bytes.size.toLong() != AES_128_KEY_BYTES) {
                        throw IOException("AES-128 key must contain exactly 16 bytes, got ${bytes.size}.")
                    }
                    temporaryFile.writeBytes(bytes)
                }
            throwIfAbortRequested(shouldAbort)
            if (keyFile.exists() && !keyFile.delete()) {
                throw IOException("Unable to replace HLS key file: ${keyFile.absolutePath}")
            }
            if (!temporaryFile.renameTo(keyFile)) {
                throw IOException("Unable to publish HLS key file: ${keyFile.absolutePath}")
            }
            AppLogger.d("HLS: Encryption key downloaded to ${keyFile.absolutePath}")
        } catch (e: CancellationException) {
            if (temporaryFile.exists() && !temporaryFile.delete()) {
                e.addSuppressed(IOException("Unable to remove HLS key staging file."))
            }
            throw e
        } catch (e: Exception) {
            if (temporaryFile.exists() && !temporaryFile.delete()) {
                e.addSuppressed(IOException("Unable to remove HLS key staging file."))
            }
            throw IOException(
                "Failed to download HLS encryption key: ${e.message}",
                e
            )
        }
    }

    /**
     * Creates a single MP4 file by concatenating an fMP4 init segment and all media segments.
     */
    internal fun createConcatenatedFmp4File(
        hlsTmpDir: File,
        segments: List<HlsPlaylistParser.MediaSegment>,
        prefix: String, // "video" or "audio"
        shouldAbort: () -> Boolean = { false }
    ): File {
        val urlSegments = requireUrlMediaSegments(segments, prefix)
        val expectedInitialization = urlSegments.firstOrNull()?.initializationSegment
            ?: throw IOException("fMP4 initialization segment is missing for $prefix.")
        if (urlSegments.any { it.initializationSegment != expectedInitialization }) {
            throw IOException("fMP4 initialization segment changes are not supported for $prefix.")
        }
        val initFile = hlsTmpDir.resolve("init_$prefix.mp4")
        val concatenatedFile = hlsTmpDir.resolve("concatenated_$prefix.mp4")
        requireNonEmptyFile(initFile, "fMP4 initialization segment for $prefix")
        val filePrefix = if (prefix == "video") "segment_" else "audio_segment_"
        val segmentFiles = urlSegments.indices.map { index ->
            hlsTmpDir.resolve("${filePrefix}${"%05d".format(index)}.m4s")
        }
        segmentFiles.forEachIndexed { index, file ->
            requireNonEmptyFile(file, "fMP4 $prefix segment $index")
        }

        try {
            throwIfAbortRequested(shouldAbort)
            concatenatedFile.outputStream().use { output ->
                AppLogger.d("HLS (fMP4): Reading $prefix init segment from ${initFile.absolutePath}")
                copyFileWithAbort(initFile, output, shouldAbort)
                segmentFiles.forEach { segmentFile ->
                    copyFileWithAbort(segmentFile, output, shouldAbort)
                }
            }
            throwIfAbortRequested(shouldAbort)
        } catch (e: CancellationException) {
            if (concatenatedFile.exists() && !concatenatedFile.delete()) {
                e.addSuppressed(IOException("Unable to remove incomplete fMP4 concatenation."))
            }
            throw e
        } catch (e: Exception) {
            if (concatenatedFile.exists() && !concatenatedFile.delete()) {
                e.addSuppressed(IOException("Unable to remove incomplete fMP4 concatenation."))
            }
            throw IOException(
                "Failed to create concatenated fMP4 file for $prefix: ${e.message}",
                e
            )
        }
        AppLogger.d("HLS (fMP4): All $prefix segments concatenated into ${concatenatedFile.absolutePath}")
        return concatenatedFile
    }

    internal fun createLocalHlsPlaylistFile(
        hlsTmpDir: File,
        segments: List<HlsPlaylistParser.MediaSegment>,
        filePrefix: String,
        playlistName: String,
        segmentExtension: String = "ts",
        initializationFileName: String? = null,
        shouldAbort: () -> Boolean = { false },
        publishCaptureMarker: Boolean = true
    ): File {
        throwIfAbortRequested(shouldAbort)
        val urlSegments = requireUrlMediaSegments(segments, filePrefix)
        if (urlSegments.isEmpty()) {
            throw IOException("Cannot create a local HLS playlist without segments.")
        }
        urlSegments.forEach { segment ->
            if (!segment.duration.isFinite() || segment.duration < 0.0) {
                throw IOException("HLS segment duration is invalid.")
            }
            if (segment.mediaSequence < 0L) {
                throw IOException("HLS media sequence cannot be negative.")
            }
        }

        val isEncrypted = urlSegments.any { it.encryptionKey != null }
        val requiresHlsPlaylist = isEncrypted || initializationFileName != null
        val baseName = playlistName.substringBeforeLast('.', playlistName)
        val finalPlaylistName = "$baseName.${if (requiresHlsPlaylist) "m3u8" else "txt"}"

        val playlistFile = hlsTmpDir.resolve(finalPlaylistName)
        val segmentFiles = urlSegments.indices.map { index ->
            hlsTmpDir.resolve("${filePrefix}${"%05d".format(index)}.$segmentExtension")
        }
        segmentFiles.forEachIndexed { index, file ->
            requireNonEmptyFile(file, "HLS segment $index")
        }
        val initializationFile = initializationFileName?.let { fileName ->
            val expectedInitialization = urlSegments.first().initializationSegment
                ?: throw IOException("HLS initialization metadata is missing.")
            if (urlSegments.any { it.initializationSegment != expectedInitialization }) {
                throw IOException("HLS initialization segment changes are not supported.")
            }
            hlsTmpDir.resolve(fileName).also { file ->
                requireNonEmptyFile(file, "HLS initialization segment")
            }
        }

        urlSegments.mapNotNull { it.encryptionKey }.forEach(::validateEncryptionKey)
        urlSegments.mapNotNull { it.encryptionKey }.distinctBy { it.uri }.forEach { key ->
            requireNonEmptyKeyFile(hlsTmpDir.resolve(encryptionKeyFileName(key.uri)))
        }

        val playlistContent = buildString {
            if (requiresHlsPlaylist) {
                appendLine("#EXTM3U")
                appendLine("#EXT-X-VERSION:${if (initializationFile != null) 7 else 3}")
                val targetDuration = ceil(urlSegments.maxOf { it.duration })
                    .coerceAtLeast(1.0)
                    .coerceAtMost(Int.MAX_VALUE.toDouble())
                    .toInt()
                appendLine("#EXT-X-TARGETDURATION:$targetDuration")
                appendLine("#EXT-X-MEDIA-SEQUENCE:${urlSegments.first().mediaSequence}")
            }

            var activeKey = initializationFile?.let {
                urlSegments.first().encryptionKey?.let { key ->
                    localKeyState(key, urlSegments.first().mediaSequence, hlsTmpDir).also {
                        appendKeyDirective(it)
                    }
                }
            }
            initializationFile?.let { file ->
                appendLine("#EXT-X-MAP:URI=\"${file.name}\"")
            }

            urlSegments.forEachIndexed { index, segment ->
                if (requiresHlsPlaylist) {
                    val segmentKey = segment.encryptionKey?.let { key ->
                        localKeyState(key, segment.mediaSequence, hlsTmpDir)
                    }
                    if (segmentKey != activeKey) {
                        if (segmentKey == null) {
                            if (activeKey != null) {
                                appendLine("#EXT-X-KEY:METHOD=NONE")
                            }
                        } else {
                            appendKeyDirective(segmentKey)
                        }
                        activeKey = segmentKey
                    }
                    if (segment.discontinuity) {
                        appendLine("#EXT-X-DISCONTINUITY")
                    }
                    appendLine("#EXTINF:${segment.duration},")
                    appendLine(segmentFiles[index].name)
                } else {
                    appendLine("file '${segmentFiles[index].absolutePath}'")
                }
            }

            if (requiresHlsPlaylist) {
                appendLine("#EXT-X-ENDLIST")
            }
        }
        throwIfAbortRequested(shouldAbort)
        publishUtf8Atomically(playlistFile, playlistContent)
        if (publishCaptureMarker) {
            publishUtf8Atomically(
                hlsTmpDir.resolve("$baseName.capture"),
                playlistFile.name
            )
        }
        throwIfAbortRequested(shouldAbort)
        AppLogger.d("Created playlist file: ${playlistFile.name}")
        return playlistFile
    }

    internal fun preparedHlsInputFile(hlsTmpDir: File, baseName: String): File? {
        val committedManifest = readHlsCaptureManifest(hlsTmpDir)
        if (committedManifest != null) {
            val fileName = when (baseName) {
                "video" -> committedManifest.videoFileName
                "audio" -> committedManifest.audioFileName
                else -> throw IOException("Unknown HLS capture stream: $baseName")
            }
            return fileName?.let { resolveCommittedHlsInput(hlsTmpDir, it) }
        }

        val allowedNames = setOf("$baseName.m3u8", "$baseName.txt")
        val marker = hlsTmpDir.resolve("$baseName.capture")
        if (marker.isFile) {
            val markedName = marker.readText(Charsets.UTF_8).trim()
            if (markedName in allowedNames) {
                hlsTmpDir.resolve(markedName).takeIf { it.isFile && it.length() > 0L }?.let {
                    return it
                }
            }
        }
        return allowedNames.asSequence()
            .map(hlsTmpDir::resolve)
            .filter { it.isFile && it.length() > 0L }
            .maxWithOrNull(compareBy<File> { it.lastModified() }.thenBy { it.extension == "m3u8" })
    }

    private fun createCaptureInput(
        hlsTmpDir: File,
        segments: List<HlsPlaylistParser.MediaSegment>,
        streamName: String,
        filePrefix: String,
        generation: String,
        shouldAbort: () -> Boolean
    ): File? {
        if (segments.isEmpty()) return null
        val urlSegments = requireUrlMediaSegments(segments, streamName)
        val initialization = urlSegments.first().initializationSegment
        if (urlSegments.any { it.initializationSegment != initialization }) {
            throw IOException("HLS $streamName initialization segment changed during capture.")
        }
        val usesFmp4 = initialization != null
        return createLocalHlsPlaylistFile(
            hlsTmpDir = hlsTmpDir,
            segments = urlSegments,
            filePrefix = filePrefix,
            playlistName = "${streamName}_capture_$generation.m3u8",
            segmentExtension = if (usesFmp4) "m4s" else "ts",
            initializationFileName = if (usesFmp4) "init_$streamName.mp4" else null,
            shouldAbort = shouldAbort,
            publishCaptureMarker = false
        )
    }

    private fun readHlsCaptureManifest(hlsTmpDir: File): HlsCaptureManifest? {
        val manifestFile = hlsTmpDir.resolve(HLS_CAPTURE_MANIFEST)
        restoreCommittedHlsManifestBackupIfNeeded(manifestFile)
        if (!manifestFile.exists()) return null
        if (!manifestFile.isFile || manifestFile.length() <= 0L) {
            throw IOException("HLS capture manifest is missing or empty: ${manifestFile.absolutePath}")
        }
        try {
            val root = JSONObject(manifestFile.readText(Charsets.UTF_8))
            val version = root.getInt("version")
            if (version != HLS_CAPTURE_VERSION) {
                throw IOException("Unsupported HLS capture manifest version: $version")
            }
            val generation = root.getString("generation")
            if (!generation.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) {
                throw IOException("Invalid HLS capture generation: $generation")
            }
            if (!root.has("video") || !root.has("audio")) {
                throw IOException("HLS capture manifest must declare both stream slots.")
            }
            val videoName = if (root.isNull("video")) null else root.getString("video")
            val audioName = if (root.isNull("audio")) null else root.getString("audio")
            if (videoName == null && audioName == null) {
                throw IOException("HLS capture manifest does not reference any stream.")
            }
            videoName?.let { validateCaptureInputName(it, "video", generation) }
            audioName?.let { validateCaptureInputName(it, "audio", generation) }
            return HlsCaptureManifest(generation, videoName, audioName)
        } catch (error: Exception) {
            if (error is IOException) throw error
            throw IOException("Failed to read HLS capture manifest: ${error.message}", error)
        }
    }

    private fun restoreCommittedHlsManifestBackupIfNeeded(manifest: File) {
        if (manifest.exists()) return
        val backup = File(manifest.parentFile, "${manifest.name}.bak")
        if (!backup.exists()) return
        if (!backup.isFile || backup.length() <= 0L) {
            throw IOException("HLS capture manifest backup is missing or empty: ${backup.absolutePath}")
        }
        if (!backup.renameTo(manifest)) {
            throw IOException("Unable to restore the committed HLS capture manifest backup.")
        }
        AppLogger.w("HLS: Restored the committed capture manifest after an interrupted publish.")
    }

    private fun validateCaptureInputName(fileName: String, streamName: String, generation: String) {
        val expectedPrefix = "${streamName}_capture_$generation."
        if (fileName.isBlank() || File(fileName).name != fileName ||
            !fileName.startsWith(expectedPrefix) ||
            (fileName.substringAfterLast('.') != "m3u8" && fileName.substringAfterLast('.') != "txt")
        ) {
            throw IOException("Invalid committed HLS $streamName input name: $fileName")
        }
    }

    private fun resolveCommittedHlsInput(hlsTmpDir: File, fileName: String): File {
        return hlsTmpDir.resolve(fileName).also { file ->
            requireNonEmptyFile(file, "Committed HLS capture input")
        }
    }

    private data class LocalKeyState(
        val method: String,
        val fileName: String,
        val iv: String
    )

    private fun StringBuilder.appendKeyDirective(state: LocalKeyState) {
        appendLine(
            "#EXT-X-KEY:METHOD=${state.method},URI=\"${state.fileName}\",IV=${state.iv}"
        )
    }

    private fun localKeyState(
        key: HlsPlaylistParser.HlsEncryptionKey,
        mediaSequence: Long,
        hlsTmpDir: File
    ): LocalKeyState {
        validateEncryptionKey(key)
        val fileName = encryptionKeyFileName(key.uri)
        requireNonEmptyKeyFile(hlsTmpDir.resolve(fileName))
        return LocalKeyState(
            method = AES_128_METHOD,
            fileName = fileName,
            iv = normalizeIv(key.iv, mediaSequence)
        )
    }

    private fun normalizeIv(explicitIv: String?, mediaSequence: Long): String {
        if (mediaSequence < 0L) {
            throw IOException("HLS media sequence cannot be negative.")
        }
        val hex = if (explicitIv == null) {
            java.lang.Long.toHexString(mediaSequence)
        } else {
            explicitIv.trim().removePrefix("0x").removePrefix("0X")
        }
        if (hex.isEmpty() || hex.length > 32 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            throw IOException("HLS AES-128 IV must be a hexadecimal value no larger than 128 bits.")
        }
        return "0x${hex.lowercase(Locale.US).padStart(32, '0')}"
    }

    private fun validateEncryptionKey(key: HlsPlaylistParser.HlsEncryptionKey) {
        if (!key.method.equals(AES_128_METHOD, ignoreCase = true)) {
            throw IOException("Unsupported HLS encryption method: ${key.method}.")
        }
        if (key.uri.isBlank()) {
            throw IOException("HLS AES-128 key URI cannot be blank.")
        }
    }

    private fun requireUrlMediaSegments(
        segments: List<HlsPlaylistParser.MediaSegment>,
        label: String
    ): List<HlsPlaylistParser.UrlMediaSegment> {
        return segments.map { segment ->
            segment as? HlsPlaylistParser.UrlMediaSegment
                ?: throw IOException("HLS $label playlist contains an unsupported media segment.")
        }
    }

    private fun requireNonEmptyFile(file: File, label: String) {
        if (!file.isFile || file.length() <= 0L) {
            throw IOException("$label is missing or empty: ${file.absolutePath}")
        }
    }

    private fun requireNonEmptyKeyFile(file: File) {
        if (!file.isFile || file.length() != AES_128_KEY_BYTES) {
            throw IOException("AES-128 key file is missing or invalid: ${file.absolutePath}")
        }
    }

    private fun throwIfAbortRequested(shouldAbort: () -> Boolean) {
        if (shouldAbort()) {
            throw CancellationException("HLS operation interrupted before completion.")
        }
    }

    private fun copyFileWithAbort(
        source: File,
        output: java.io.OutputStream,
        shouldAbort: () -> Boolean
    ) {
        source.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                throwIfAbortRequested(shouldAbort)
                val bytesRead = input.read(buffer)
                if (bytesRead < 0) break
                output.write(buffer, 0, bytesRead)
            }
        }
    }

    private fun publishUtf8Atomically(target: File, content: String) {
        val parent = target.parentFile
            ?: throw IOException("HLS snapshot has no parent directory: ${target.absolutePath}")
        if ((!parent.exists() && !parent.mkdirs()) || !parent.isDirectory) {
            throw IOException("Unable to create HLS snapshot directory: ${parent.absolutePath}")
        }
        val staging = File(parent, "${target.name}.tmp")
        val backup = File(parent, "${target.name}.bak")
        try {
            if (staging.exists() && !staging.delete()) {
                throw IOException("Unable to clear stale HLS snapshot staging file.")
            }
            staging.writeText(content, Charsets.UTF_8)
            if (backup.exists() && !backup.delete()) {
                throw IOException("Unable to clear stale HLS snapshot backup file.")
            }
            if (target.exists() && !target.renameTo(backup)) {
                throw IOException("Unable to stage the previous HLS snapshot.")
            }
            if (!staging.renameTo(target)) {
                if (backup.exists() && !backup.renameTo(target)) {
                    throw IOException("Unable to publish or restore HLS snapshot: ${target.absolutePath}")
                }
                throw IOException("Unable to publish HLS snapshot: ${target.absolutePath}")
            }
            if (backup.exists() && !backup.delete()) {
                AppLogger.w("HLS: Unable to delete obsolete snapshot backup ${backup.name}.")
            }
        } catch (error: Exception) {
            if (staging.exists() && !staging.delete()) {
                error.addSuppressed(IOException("Unable to remove HLS snapshot staging file."))
            }
            if (!target.exists() && backup.exists() && !backup.renameTo(target)) {
                error.addSuppressed(IOException("Unable to restore the previous HLS snapshot."))
            }
            throw IOException("Failed to publish HLS snapshot ${target.name}: ${error.message}", error)
        }
    }

    private fun addHlsOutputArguments(
        arguments: MutableList<String>,
        hasVideo: Boolean,
        hasAudio: Boolean,
        videoCodec: String?,
        finalOutputPath: String
    ) {
        arguments.apply {
            when {
                hasVideo && hasAudio -> {
                    add("-map"); add("0:v?"); add("-map"); add("1:a?")
                }
                hasVideo -> {
                    add("-map"); add("0")
                }
                hasAudio -> {
                    add("-map"); add("0:a?")
                }
            }

            if (hasVideo &&
                (videoCodec?.startsWith("hvc1") == true || videoCodec?.startsWith("dvh1") == true)
            ) {
                add("-c:v"); add("libx264")
                add("-preset"); add("veryfast")
                add("-crf"); add("23")
                add("-pix_fmt"); add("yuv420p")
                if (hasAudio) {
                    add("-c:a"); add("copy")
                }
            } else {
                add("-c"); add("copy")
            }

            if (hasAudio) {
                add("-bsf:a"); add("aac_adtstoasc")
            }
            add("-movflags"); add("+faststart")
            add("-y"); add(finalOutputPath)
        }
    }

    private fun executeHlsMerge(
        arguments: List<String>,
        totalDurationSeconds: Double,
        onMergeProgress: ((percentage: Int) -> Unit)?,
        shouldAbort: () -> Boolean
    ): FFmpegSession {
        throwIfAbortRequested(shouldAbort)
        AppLogger.d("DownloaderUtils: Executing HLS merge with arguments: $arguments")

        val latch = CountDownLatch(1)
        lateinit var finalSession: FFmpegSession
        val sessionId = AtomicLong(-1L)
        val session = FFmpegKit.executeAsync(
            arguments.joinToString(" "),
            { completedSession ->
                finalSession = completedSession
                if (!ReturnCode.isSuccess(completedSession.returnCode)) {
                    AppLogger.e("FFmpeg merge failed with return code ${completedSession.returnCode}. Log: ${completedSession.allLogsAsString}")
                }
                latch.countDown()
            },
            { log -> AppLogger.d("FFmpeg: ${log.message}") },
            { statistics ->
                if (shouldAbort()) {
                    sessionId.get().takeIf { it >= 0L }?.let(FFmpegKit::cancel)
                    return@executeAsync
                }
                if (onMergeProgress != null && totalDurationSeconds > 0) {
                    val totalDurationMillis = (totalDurationSeconds * 1000).toLong()
                    if (statistics.time > 0) {
                        val percentage = ((statistics.time * 100) / totalDurationMillis).toInt()
                        onMergeProgress(percentage.coerceIn(0, 100))
                    }
                }
            }
        )
        sessionId.set(session.sessionId)

        try {
            while (!latch.await(250L, TimeUnit.MILLISECONDS)) {
                if (shouldAbort()) {
                    FFmpegKit.cancel(session.sessionId)
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            FFmpegKit.cancel(session.sessionId)
            throw IOException("FFmpeg merge was interrupted.", e)
        }
        throwIfAbortRequested(shouldAbort)
        return finalSession
    }

    private fun addPlaylistArguments(arguments: MutableList<String>, playlistFile: File) {
        val isEncrypted = playlistFile.extension == "m3u8"
        arguments.apply {
            if (isEncrypted) {
                add("-protocol_whitelist"); add("file,pipe,crypto")
                add("-allowed_extensions"); add("ALL")
            } else {
                add("-f"); add("concat")
                add("-safe"); add("0")
            }
            add("-i"); add(playlistFile.absolutePath)
        }
    }
}
