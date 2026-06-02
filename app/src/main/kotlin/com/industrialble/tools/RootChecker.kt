package com.industrialble.tools

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Verifica si el dispositivo tiene root y qué capacidades están disponibles.
 */
object RootChecker {

    data class RootStatus(
        val isRooted: Boolean = false,
        val hasBusybox: Boolean = false,
        val hasSuBinary: Boolean = false,
        val hasCustomKernel: Boolean = false,
        val hasWiFiMonitorMode: Boolean = false, // generalmente falso en smartphones
        val details: String = ""
    )

    fun check(context: Context): RootStatus {
        val checks = mutableListOf<String>()

        // 1. Buscar binarios SU comunes
        val suPaths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/system/app/Superuser.apk",
            "/system/app/SuperSU.apk",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/sbin/su",
            "/su/bin/su",
            "/magisk/.magisk"
        )
        val foundSu = suPaths.any { File(it).exists() }
        if (foundSu) checks.add("SU binary found")

        // 2. Build tags de test
        val buildTags = android.os.Build.TAGS ?: ""
        val isTestBuild = buildTags.contains("test-keys")
        if (isTestBuild) checks.add("Test-keys build")

        // 3. Intentar ejecutar su via command
        var suWorks = false
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readLine()
            if (output != null && output.contains("uid=0")) {
                suWorks = true
                checks.add("su command works (UID 0)")
            }
            process.waitFor()
        } catch (_: Exception) {
            // No root
        }

        // 4. Buscar busybox
        val busyboxPaths = listOf(
            "/system/xbin/busybox",
            "/system/bin/busybox",
            "/data/local/bin/busybox"
        )
        val hasBusybox = busyboxPaths.any { File(it).exists() }
        if (hasBusybox) checks.add("Busybox found")

        // 5. Verificar si es kernel custom
        val kernelVersion = System.getProperty("os.version") ?: ""
        val hasCustomKernel = !kernelVersion.contains("3.4") && !kernelVersion.contains("4.") &&
                kernelVersion.isNotEmpty() && suWorks
        if (hasCustomKernel) checks.add("Custom kernel")

        val isRooted = foundSu || isTestBuild || suWorks

        val details = if (checks.isEmpty()) {
            "No se detectó root. Funcionalidades limitadas."
        } else {
            checks.joinToString("\n• ", "• ")
        }

        return RootStatus(
            isRooted = isRooted,
            hasBusybox = hasBusybox,
            hasSuBinary = foundSu,
            hasCustomKernel = hasCustomKernel,
            hasWiFiMonitorMode = false, // Los smartphones NO tienen monitor mode
            details = details
        )
    }

    /**
     * Ejecuta un comando como root y devuelve el output.
     */
    fun execRootCommand(command: String): Result<String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val stdout = BufferedReader(InputStreamReader(process.inputStream))
            val stderr = BufferedReader(InputStreamReader(process.errorStream))
            val output = stdout.readText().trim()
            val error = stderr.readText().trim()
            process.waitFor()
            if (output.isNotEmpty()) {
                Result.success(output)
            } else if (error.isNotEmpty()) {
                Result.success(error)
            } else {
                Result.success("OK")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
