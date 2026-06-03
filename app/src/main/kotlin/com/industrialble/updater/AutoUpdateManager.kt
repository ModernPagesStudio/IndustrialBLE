package com.industrialble.updater

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.app.NotificationCompat
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
        private const val APK_FILENAME = "HackDroid-Update.apk"
        private const val CHANNEL_ID = "hackdroid_updates"
        private const val NOTIFY_ID = 1001
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Actualizaciones HackDroid",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones de actualizaciones automáticas"
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * Resultado del progreso de descarga con estado incluido.
     */
    data class DownloadProgress(
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
        val status: Int = DownloadManager.STATUS_PENDING
    )

    /**
     * Inicia la descarga de la APK usando DownloadManager.
     * @param downloadUrl URL directa del archivo APK
     * @param onComplete callback cuando la descarga finalice
     */
    fun downloadAndInstall(
        downloadUrl: String,
        onComplete: (() -> Unit)? = null
    ) {
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
            .setTitle("HackDroid - Actualización")
            .setDescription("Descargando nueva versión...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                APK_FILENAME
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setRequiresCharging(false)
            .setRequiresDeviceIdle(false)

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadId = manager.enqueue(request)
    }

    /**
     * Obtiene el progreso actual de la descarga con estado incluido.
     * Reporta correctamente cuando la descarga terminó o falló.
     */
    fun getDownloadProgress(): DownloadProgress {
        if (downloadId < 0) return DownloadProgress(status = DownloadManager.STATUS_FAILED)
        return try {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = manager.query(query)
            if (cursor.moveToFirst()) {
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                cursor.close()
                DownloadProgress(
                    downloadedBytes = downloaded,
                    totalBytes = total,
                    status = status
                )
            } else {
                cursor.close()
                DownloadProgress(status = DownloadManager.STATUS_FAILED)
            }
        } catch (_: Exception) {
            DownloadProgress(status = DownloadManager.STATUS_FAILED)
        }
    }

    /**
     * Verifica si la descarga finalizó (exitosa o fallida).
     */
    fun isDownloadFinished(): Boolean {
        val progress = getDownloadProgress()
        return progress.status == DownloadManager.STATUS_SUCCESSFUL ||
               progress.status == DownloadManager.STATUS_FAILED
    }

    /**
     * Obtiene el motivo del fallo si la descarga falló.
     */
    fun getDownloadError(): String {
        if (downloadId < 0) return "No hay descarga activa"
        return try {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = manager.query(query)
            if (cursor.moveToFirst()) {
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                cursor.close()
                when (status) {
                    DownloadManager.STATUS_FAILED -> {
                        when (reason) {
                            DownloadManager.ERROR_CANNOT_RESUME -> "No se puede reanudar"
                            DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Dispositivo no encontrado"
                            DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "El archivo ya existe"
                            DownloadManager.ERROR_FILE_ERROR -> "Error de archivo"
                            DownloadManager.ERROR_HTTP_DATA_ERROR -> "Error HTTP al descargar"
                            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Espacio insuficiente"
                            DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Demasiadas redirecciones"
                            DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Código HTTP no manejado"
                            DownloadManager.ERROR_UNKNOWN -> "Error desconocido"
                            else -> "Código de error: $reason"
                        }
                    }
                    DownloadManager.STATUS_PAUSED -> {
                        when (reason) {
                            DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Esperando WiFi"
                            DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Esperando red"
                            DownloadManager.PAUSED_WAITING_TO_RETRY -> "Reintentando..."
                            DownloadManager.PAUSED_UNKNOWN -> "Pausada (razón desconocida)"
                            else -> "Pausada"
                        }
                    }
                    else -> "Estado: $status"
                }
            } else {
                cursor.close()
                "Descarga no encontrada"
            }
        } catch (e: Exception) {
            "Error al consultar: ${e.message}"
        }
    }

    /**
     * Instala la APK ya descargada usando el instalador del sistema.
     */
    private fun installApk() {
        try {
            unregisterReceiver()

            val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            val apkFile = File(downloadsDir, APK_FILENAME)

            if (!apkFile.exists()) {
                showNotification("Error", "Archivo APK no encontrado")
                return
            }

            // Verificar permiso para instalar apps desconocidas (Android 8+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    showInstallSettingsNotification()
                    return
                }
            }

            // Abrir instalador directamente (sin notificación intermedia)
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(installIntent)
        } catch (e: Exception) {
            showNotification("Error", "Error al instalar: ${e.message}")
        }
    }

    private fun showNotification(title: String, message: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(NOTIFY_ID, notification)
    }

    private fun showInstallSettingsNotification() {
        val settingsIntent = Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val pendingIntent = PendingIntent.getActivity(
            context, 1, settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚙️ Permiso requerido")
            .setContentText("Habilita 'Instalar apps desconocidas' para HackDroid")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        nm.notify(NOTIFY_ID, notification)

        // También abrir ajustes directamente
        context.startActivity(settingsIntent)
    }

    fun openInstallSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun cleanup() {
        downloadId = -1
        unregisterReceiver()
    }

    private fun unregisterReceiver() {
        try {
            downloadReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) { }
        downloadReceiver = null
    }
}
