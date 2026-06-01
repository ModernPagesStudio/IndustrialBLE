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
    val isNewer: Boolean
)

/**
 * Verifica la última versión disponible en GitHub Releases.
 * Usa la API pública de GitHub (sin autenticación, sujeta a rate limiting).
 */
class GitHubReleaseChecker(
    private val repoOwner: String = "ModernPagesStudio",
    private val repoName: String = "IndustrialBLE"
) {

    companion object {
        private const val API_URL = "https://api.github.com/repos/%s/%s/releases/latest"
        private const val TIMEOUT_MS = 10_000
    }

    /**
     * Consulta la API de GitHub y devuelve la información de la última release.
     * @param currentVersion versión actual en formato "1.0.0"
     * @return ReleaseInfo con datos de la última release
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
                // Si hay error (rate limit, repo privado, etc.)
                connection.disconnect()
                return@withContext null
            }

            val response = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            val json = JSONObject(response)
            val tagName = json.optString("tag_name", "") // ej: "v1.1.0"
            val releaseNotes = json.optString("body", "Sin notas de lanzamiento.")
            val assets = json.optJSONArray("assets")

            // Buscar el primer APK en los assets
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

            // Si no se encontró un APK en assets, intentar con la URL del release tag
            if (downloadUrl.isBlank()) {
                downloadUrl = "https://github.com/$repoOwner/$repoName/releases/download/$tagName/app-release.apk"
            }

            val latestVersion = tagName.removePrefix("v") // "1.1.0"
            val isNewer = compareVersions(latestVersion, currentVersion) > 0

            ReleaseInfo(
                latestVersion = latestVersion,
                downloadUrl = downloadUrl,
                releaseNotes = releaseNotes.trim(),
                isNewer = isNewer
            )
        } catch (e: Exception) {
            // Error de red, timeout, etc.
            null
        }
    }

    /**
     * Compara versiones semánticas "1.0.0" > "0.9.9"
     * @return positivo si v1 > v2, negativo si v1 < v2, 0 si igual
     */
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
