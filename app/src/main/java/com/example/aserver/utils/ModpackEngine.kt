package com.example.aserver.utils

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

data class ModDownloadInfo(
    val path: String,
    val downloadUrl: String,
    val fileSize: Long = 0L
)

object ModpackEngine {

    suspend fun processMrPack(
        context: Context,
        fileUri: Uri,
        targetServerDir: File,
        onProgress: (Float, String) -> Unit
    ) = withContext(Dispatchers.IO) {
        targetServerDir.mkdirs()

        val tempZipFile = File(context.cacheDir, "temp_modpack.zip")
        context.contentResolver.openInputStream(fileUri)?.use { input ->
            FileOutputStream(tempZipFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Modpack dosyası okunamadı.")

        val modDownloads = mutableListOf<ModDownloadInfo>()

        try {
            ZipInputStream(tempZipFile.inputStream().buffered()).use { zipInput ->
                var entry = zipInput.nextEntry
                while (entry != null) {
                    val entryName = entry.name.replace("\\", "/")

                    when {
                        entryName == "modrinth.index.json" -> {
                            modDownloads += parseModIndex(readCurrentEntryText(zipInput))
                        }

                        entryName.startsWith("overrides/") -> {
                            extractOverrideEntry(
                                targetServerDir = targetServerDir,
                                entryName = entryName,
                                isDirectory = entry.isDirectory,
                                zipInput = zipInput
                            )
                        }
                    }

                    zipInput.closeEntry()
                    entry = zipInput.nextEntry
                }
            }
        } finally {
            if (tempZipFile.exists()) {
                tempZipFile.delete()
            }
        }

        if (modDownloads.isEmpty()) {
            withContext(Dispatchers.Main) {
                onProgress(1f, "Modpack kurulumu tamamlandı.")
            }
            return@withContext
        }

        val completedCount = AtomicInteger(0)
        val semaphore = Semaphore(5)
        val totalCount = modDownloads.size.toFloat()

        coroutineScope {
            modDownloads.map { modInfo ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val statusMessage = try {
                            downloadMod(targetServerDir, modInfo)
                        } catch (_: Exception) {
                            "Hata: ${modInfo.path}"
                        }

                        val progress = completedCount.incrementAndGet().toFloat() / totalCount
                        withContext(Dispatchers.Main) {
                            onProgress(progress, statusMessage)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private fun parseModIndex(indexContent: String): List<ModDownloadInfo> {
        val root = JSONObject(indexContent)
        val filesArray = root.optJSONArray("files") ?: return emptyList()
        val downloads = mutableListOf<ModDownloadInfo>()

        for (i in 0 until filesArray.length()) {
            val fileObject = filesArray.optJSONObject(i) ?: continue
            val envObject = fileObject.optJSONObject("env")
            val serverEnv = envObject?.optString("server", "required") ?: "required"
            if (serverEnv.equals("unsupported", ignoreCase = true)) {
                continue
            }

            val path = fileObject.optString("path")
            val downloadUrl = fileObject.optJSONArray("downloads")?.optString(0).orEmpty()
            if (path.isBlank() || downloadUrl.isBlank()) {
                continue
            }

            downloads += ModDownloadInfo(
                path = path,
                downloadUrl = downloadUrl,
                fileSize = fileObject.optLong("fileSize", 0L)
            )
        }

        return downloads
    }

    private fun readCurrentEntryText(zipInput: ZipInputStream): String {
        val buffer = ByteArray(8 * 1024)
        val byteStream = ByteArrayOutputStream()
        var read = zipInput.read(buffer)

        while (read != -1) {
            byteStream.write(buffer, 0, read)
            read = zipInput.read(buffer)
        }

        return byteStream.toString(Charsets.UTF_8.name())
    }

    private fun extractOverrideEntry(
        targetServerDir: File,
        entryName: String,
        isDirectory: Boolean,
        zipInput: ZipInputStream
    ) {
        val relativePath = entryName.removePrefix("overrides/").trimStart('/')
        if (relativePath.isBlank()) {
            return
        }

        val destination = File(targetServerDir, relativePath)
        if (isDirectory) {
            destination.mkdirs()
            return
        }

        destination.parentFile?.mkdirs()
        BufferedOutputStream(FileOutputStream(destination)).use { output ->
            zipInput.copyTo(output)
        }
    }

    private fun downloadMod(targetServerDir: File, modInfo: ModDownloadInfo): String {
        val finalFile = File(targetServerDir, modInfo.path)
        if (finalFile.exists()) {
            return "Atlandı: ${modInfo.path}"
        }

        val partFile = File(targetServerDir, modInfo.path + ".part")
        finalFile.parentFile?.mkdirs()
        partFile.parentFile?.mkdirs()

        if (partFile.exists()) {
            partFile.delete()
        }

        val connection = (URL(modInfo.downloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AServerApp")
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("HTTP $responseCode")
            }

            connection.inputStream.use { input ->
                BufferedOutputStream(FileOutputStream(partFile)).use { output ->
                    input.copyTo(output)
                }
            }

            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }

            return "İndirildi: ${modInfo.path}"
        } finally {
            connection.disconnect()
        }
    }
}
