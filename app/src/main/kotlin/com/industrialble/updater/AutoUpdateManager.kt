package com.industrialble.updater

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Gestiona la descarga e instalación de la APK más reciente.
 * Usa DownloadManager para la descarga en segundo plano y FileProvider
 * para compartir el archivo con el instalador del sistema.
 */
class AutoUpdateManager(
    private val context: Context
) {
    private var downloadId: Long = -1
    private var downloadReceiver: BroadcastReceiver? = null
    private var onDownloadComplete: (() -> Unit)? = null

    companion object {
        private const val APK_FILENAME = "IndustrialBLE-Update.apk"
    }

    /**
     * Inicia la descarga de la APK usando DownloadManager.
     * @param downloadUrl URL directa del archivo APK
     * @param onComplete callback cuando la descarga finalice
     */
    fun downloadAndInstall(downloadUrl: String, onComplete: (() -> Unit)? = null) {
        onDownloadComplete = onComplete

        // Registrar receptor para cuando termine la descarga
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    onDownloadComplete?.invoke()
                    installApk()
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            context.registerReceiver(
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }

        // Limpiar descargas previas
        val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val existingFile = File(downloadsDir, APK_FILENAME)
        if (existingFile.exists()) {
            existingFile.delete()
        }

        // Configurar la descarga
        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle("IndustrialBLE - Actualización")
            .setDescription("Descargando nueva versión...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                APK_FILENAME
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadId = manager.enqueue(request)
    }

    /**
     * Instala la APK ya descargada usando el instalador del sistema.
     * En Android 8+ verifica el permiso REQUEST_INSTALL_PACKAGES primero.
     */
    private fun installApk() {
        try {
            // Desregistrar receiver
            unregisterReceiver()

            val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            val apkFile = File(downloadsDir, APK_FILENAME)

            if (!apkFile.exists()) {
                Toast.makeText(context, "Error: Archivo APK no encontrado", Toast.LENGTH_LONG).show()
                return
            }

            // Verificar permiso para instalar apps desconocidas (Android 8+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    // Abrir ajustes para que el usuario habilite el permiso
                    val settingsIntent = Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(settingsIntent)
                    Toast.makeText(
                        context,
                        "Habilita 'Instalar apps desconocidas' para IndustrialBLE y vuelve a intentar",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
            }

            // Crear URI usando FileProvider
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )

            // Intent de instalación
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(
                context,
                "Error al instalar: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Abre los ajustes de instalación de apps desconocidas para este paquete.
     */
    fun openInstallSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    /**
     * Limpia el receiver y referencia.
     */
    fun cleanup() {
        unregisterReceiver()
    }

    private fun unregisterReceiver() {
        try {
            downloadReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) { }
        downloadReceiver = null
    }
}
