package com.imankoppai.mediaanvil.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Downloads a release APK and hands it to the system package installer. */
object AppUpdateInstaller {
    const val MAX_DOWNLOAD_BYTES = 300L * 1024 * 1024

    fun apkFile(context: Context): File = File(File(context.filesDir, "update"), "mediaanvil-update.apk")

    /** Downloads to a temporary file and publishes it only after SHA-256 verification. */
    fun downloadApk(
        context: Context,
        url: String,
        sha256Url: String,
        onProgress: (Int) -> Unit,
    ): File {
        requireGitHubHttpsUrl(url)
        requireGitHubHttpsUrl(sha256Url)
        val target = apkFile(context)
        val temporary = File(target.parentFile, "${target.name}.part")
        target.parentFile?.mkdirs()
        temporary.delete()
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "MediaAnvil-Android")
            check(connection.responseCode in 200..299) { "http_${connection.responseCode}" }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        check(written <= MAX_DOWNLOAD_BYTES) { "response_too_large" }
                        output.write(buffer, 0, read)
                        if (total > 0) {
                            val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            check(temporary.length() > 0L) { "empty_download" }
            val expected = parseExpectedSha256(downloadChecksum(sha256Url))
            val actual = sha256(temporary)
            check(actual.equals(expected, ignoreCase = true)) { "sha256_mismatch" }
            if (target.exists()) check(target.delete()) { "old_update_delete_failed" }
            check(temporary.renameTo(target)) { "update_publish_failed" }
            return target
        } catch (failure: Throwable) {
            temporary.delete()
            throw failure
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadChecksum(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "MediaAnvil-Android")
            check(connection.responseCode in 200..299) { "checksum_http_${connection.responseCode}" }
            val length = connection.contentLengthLong
            check(length <= 8 * 1024) { "checksum_response_too_large" }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val text = reader.readText()
                check(text.length <= 8 * 1024) { "checksum_response_too_large" }
                text
            }
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseExpectedSha256(text: String): String =
        Regex("(?i)(?<![0-9a-f])[0-9a-f]{64}(?![0-9a-f])")
            .find(text)?.value?.lowercase()
            ?: error("invalid_sha256_file")

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun requireGitHubHttpsUrl(value: String) {
        val url = URL(value)
        require(url.protocol.equals("https", ignoreCase = true)) { "update_url_not_https" }
        require(url.host.equals("github.com", ignoreCase = true)) { "update_url_not_github" }
    }

    /** Android 8+: the user must allow this app to install packages once. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
