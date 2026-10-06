package com.example.data.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

sealed interface AssetResolutionResult {
    data class Success(val resolvedCommand: String, val resolvedAssets: List<File>) : AssetResolutionResult
    data class MissingAsset(val assetPath: String) : AssetResolutionResult
}

class AssetResolver(private val context: Context) {

    val assetsDir: File = File(context.filesDir, "ffmpeg_assets").apply {
        if (!exists()) mkdirs()
    }

    /**
     * Lists all imported FFmpeg assets stored in app storage.
     */
    fun listAssets(): List<File> {
        return assetsDir.listFiles()?.filter { it.isFile && it.length() > 0 }?.sortedBy { it.name } ?: emptyList()
    }

    /**
     * Imports an external file via URI and persists it into the app-managed FFmpeg assets directory.
     */
    suspend fun importAsset(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        try {
            var fileName = "asset_${System.currentTimeMillis()}"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    val resolvedName = cursor.getString(nameIndex)
                    if (!resolvedName.isNullOrBlank()) {
                        fileName = resolvedName
                    }
                }
            }

            // Sanitize filename to avoid shell/filter parser escaping issues while preserving extension
            val extension = fileName.substringAfterLast('.', "")
            val baseName = fileName.substringBeforeLast('.').replace("[^a-zA-Z0-9_.-]".toRegex(), "_")
            val sanitizedFileName = if (extension.isNotBlank()) "$baseName.$extension" else baseName

            val destFile = File(assetsDir, sanitizedFileName)
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) {
                    return@withContext Result.failure(IllegalStateException("Cannot open input stream for: $uri"))
                }
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            if (!destFile.exists() || destFile.length() == 0L) {
                return@withContext Result.failure(IllegalStateException("Failed to import asset or file is empty."))
            }

            Result.success(destFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Deletes an imported asset.
     */
    fun deleteAsset(file: File): Boolean {
        return try {
            if (file.parentFile?.canonicalPath == assetsDir.canonicalPath) {
                file.delete()
            } else false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Resolves generic external local media assets in the FFmpeg command.
     * Looks for movie=, amovie=, or secondary -i <relative_path>.
     * If an asset cannot be found, returns MissingAsset with the exact missing path.
     */
    fun resolveAssetsInCommand(command: String): AssetResolutionResult {
        var currentCommand = command
        val resolvedAssets = mutableListOf<File>()

        // 1. Match movie=... and amovie=... filter sources
        val filterMovieRegex = Regex("""\b(a?movie\s*=\s*)(?:'([^']+)'|"([^"]+)"|([^\s:;\[\]',]+))""")

        for (match in filterMovieRegex.findAll(command)) {
            val prefix = match.groupValues[1] // "movie=" or "amovie="
            val rawPath = match.groupValues[2].ifEmpty {
                match.groupValues[3].ifEmpty { match.groupValues[4] }
            }.trim()

            if (rawPath.isBlank()) continue

            val resolvedFile = findAssetFile(rawPath)
            if (resolvedFile == null || !resolvedFile.exists()) {
                return AssetResolutionResult.MissingAsset(rawPath)
            }

            resolvedAssets.add(resolvedFile)
            val originalMatchStr = match.value
            val replacement = "${prefix}'${resolvedFile.absolutePath}'"
            currentCommand = currentCommand.replace(originalMatchStr, replacement)
        }

        // 2. Match secondary -i inputs (relative paths that are not {input} macro,
        // virtual FFmpeg inputs, or network URLs).
        //
        // IMPORTANT: `-f lavfi -i color=...` is a generated/virtual input, NOT an
        // external asset. Treating the lavfi graph as a filename is what caused
        // commands such as `color=c=red:s=320x240:d=2` to be rejected by the app
        // before FFmpeg ever received them.
        val inputArgRegex = Regex("""(?<=\s)-i\s+['"]?([^'"\s]+)['"]?""")
        for (match in inputArgRegex.findAll(currentCommand)) {
            val inputPath = match.groupValues[1]

            // FFmpeg virtual/filter inputs are not imported assets. The most
            // important case for OpenCL smoke tests is `-f lavfi -i color=...`.
            if (isVirtualFfmpegInput(currentCommand, match) ||
                inputPath == "{input}" ||
                inputPath == "-" ||
                inputPath.startsWith("/") ||
                inputPath.startsWith("http://") ||
                inputPath.startsWith("https://") ||
                inputPath.startsWith("rtmp://") ||
                inputPath.startsWith("content://") ||
                inputPath.startsWith("pipe:") ||
                inputPath.startsWith("fd:") ||
                inputPath.startsWith("data:")) {
                continue
            }

            val resolvedFile = findAssetFile(inputPath)
            if (resolvedFile == null || !resolvedFile.exists()) {
                return AssetResolutionResult.MissingAsset(inputPath)
            }

            resolvedAssets.add(resolvedFile)
            currentCommand = currentCommand.replaceFirst("-i $inputPath", "-i '${resolvedFile.absolutePath}'")
            currentCommand = currentCommand.replaceFirst("-i '$inputPath'", "-i '${resolvedFile.absolutePath}'")
            currentCommand = currentCommand.replaceFirst("-i \"$inputPath\"", "-i '${resolvedFile.absolutePath}'")
        }

        return AssetResolutionResult.Success(currentCommand, resolvedAssets)
    }


    /**
     * Returns true when an -i argument belongs to FFmpeg's virtual lavfi input
     * format rather than a user-provided file. Only the `-f lavfi -i ...`
     * relationship is used here, so normal relative media files still require
     * importing into FFmpeg Assets.
     */
    private fun isVirtualFfmpegInput(command: String, inputMatch: MatchResult): Boolean {
        val prefix = command.substring(0, inputMatch.range.first)
        return Regex(
            """(?:^|\s)-f\s+['\"]?lavfi['\"]?\s*$""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(prefix)
    }

    /**
     * Resolves a referenced asset path against the app-managed assets directory.
     */
    fun findAssetFile(path: String): File? {
        val directFile = File(path)
        if (directFile.isAbsolute) {
            return if (directFile.exists() && directFile.length() > 0) directFile else null
        }

        // Check relative inside app assetsDir
        val localAsset = File(assetsDir, path)
        if (localAsset.exists() && localAsset.length() > 0) {
            return localAsset
        }

        // Check by base name if path was sub-pathed (e.g. assets/watermark.png -> watermark.png)
        val baseName = path.substringAfterLast('/')
        val fallbackAsset = File(assetsDir, baseName)
        if (fallbackAsset.exists() && fallbackAsset.length() > 0) {
            return fallbackAsset
        }

        return null
    }

    /**
     * Generates sensible automatic FFmpeg threading for complex filtergraphs
     * based on device CPU core count without changing user-specified threading options.
     */
    fun applyOptimalFilterThreading(command: String): String {
        // If user already specified filter_complex_threads, do not override
        if (command.contains("-filter_complex_threads") || command.contains("filter_complex_threads")) {
            return command
        }

        // Only apply if complex filtergraph is present
        if (!command.contains("-filter_complex") && !command.contains("filter_complex")) {
            return command
        }

        val availableCores = Runtime.getRuntime().availableProcessors()
        val optimalThreads = when {
            availableCores >= 8 -> (availableCores - 2).coerceAtLeast(4)
            availableCores >= 4 -> (availableCores - 1).coerceAtLeast(3)
            availableCores >= 2 -> 2
            else -> 1
        }

        return if (command.contains("-filter_complex")) {
            command.replaceFirst("-filter_complex", "-filter_complex_threads $optimalThreads -filter_complex")
        } else {
            command.replaceFirst("filter_complex", "-filter_complex_threads $optimalThreads filter_complex")
        }
    }
}
