package com.industrialble.updater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resultado de la verificación de actualización en GitHub.
 */
data class ReleaseInfo(
    val latestVersion: String,
    val downloadUrl: String,
    val releaseNotes: String,
    val isNewer: Boolean,
    val publishedAt: String = "",
    val isContinuous: Boolean = false
)

/**
 * Verifica la última versión disponible en GitHub Releases.
 *
 * Soporta dos modos:
 * 1. Releases con versionado semántico (v1.0.0, v1.1.0, etc.)
 * 2. Release "continuous" que se actualiza automáticamente en cada build de GitHub Actions
 *    (sin necesidad de versionado manual)
 */
class GitHubReleaseChecker(
    private val repoOwner: String = "ModernPagesStudio",
    private val repoName: String = "IndustrialBLE"
) {

    companion object {
        private const val API_URL = "https://api.github.com/repos/%s/%s/releases/latest"
        private const val TIMEOUT_MS = 10_000
        private const val CONTINUOUS_TAG = "continuous"
    }

    /**
     * Consulta la API de GitHub y devuelve la información de la última release.
     * @param currentVersion versión actual en formato "1.0.0"
     * @return ReleaseInfo o null si hay error
     */
    suspend fun checkForUpdate(currentVersion: String): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL(String.format(API_URL, repoOwner, repoName))
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "IndustrialBLE-Updater")
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                connection.disconnect()
                return@withContext null
            }

            val response = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            val json = JSONObject(response)
            val tagName = json.optString("tag_name", "")
            val releaseNotes = json.optString("body", "Sin notas de lanzamiento.")
            val publishedAt = json.optString("published_at", "")
            val assets = json.optJSONArray("assets")

            // Buscar APK en los assets
            var downloadUrl = ""
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk")) {
                        downloadUrl = asset.optString("browser_download_url", "")
                        break
                    }
                }
            }

            if (downloadUrl.isBlank()) {
                downloadUrl = "https://github.com/$repoOwner/$repoName/releases/download/$tagName/app-debug.apk"
            }

            // === DETECTAR SI ES RELEASE CONTINUA (GitHub Actions) ===
            val isContinuous = tagName == CONTINUOUS_TAG

            val isNewer = if (isContinuous) {
                // Siempre mostrar como disponible si se encontró la release
                true
            } else {
                // Comparación semántica normal
                val latestVersion = tagName.removePrefix("v")
                compareVersions(latestVersion, currentVersion) > 0
            }

            ReleaseInfo(
                latestVersion = if (isContinuous) "Build ${publishedAt.take(10)}" else tagName.removePrefix("v"),
                downloadUrl = downloadUrl,
                releaseNotes = releaseNotes.trim(),
                isNewer = isNewer,
                publishedAt = publishedAt,
                isContinuous = isContinuous
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }

        for (i in 0 until maxOf(parts1.size, parts2.size)) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1 - p2
        }
        return 0
    }
}
