package com.persistor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/** Фоновая обработка: работает с уведомлением, не прерывается при сворачивании приложения. */
class ProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var running = false

    private val nm get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        val job = ProcessingState.job
        // startForeground нужно вызвать сразу, иначе система завершит приложение
        ServiceCompat.startForeground(
            this, ID_PROGRESS, progressNotification("Запуск…", 0, job?.images?.size ?: 0), fgsType()
        )
        if (job == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        running = true

        val wl = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "persistor:process")
        wl.acquire(30 * 60 * 1000L)

        scope.launch {
            val finalResult: ProcessResult = try {
                val res = Processor(applicationContext).run(
                    job.images, job.outputTree, job.archiveTree, job.settings
                ) { msg, cur, tot ->
                    ProcessingState.progress.value = ProcessProgress(true, msg, cur, tot)
                    nm.notify(ID_PROGRESS, progressNotification(msg, cur, tot))
                }
                if (res is ProcessResult.Success) {
                    val share = try { prepareShareUri(res) } catch (e: Exception) { null }
                    res.copy(shareUri = share)
                } else res
            } catch (e: Throwable) {
                ProcessResult.Failure("Ошибка: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                if (wl.isHeld) wl.release()
            }

            if (finalResult is ProcessResult.Success) {
                ProcessingState.lastSuccess = finalResult
                ProcessingState.lastPlan = job.mail
                try {
                    FolderStore(applicationContext).addHistory(
                        HistoryEntry(
                            id = finalResult.createdAt,
                            zipName = finalResult.zipName,
                            sizeBytes = finalResult.zipSize,
                            files = finalResult.files,
                            to = job.mail?.to.orEmpty(),
                            gmailOpenedAt = null,
                        )
                    )
                } catch (e: Exception) {
                    // статистика не должна ломать основной процесс
                }
            }

            ProcessingState.progress.value = ProcessProgress()
            ProcessingState.result.value = finalResult
            stopForeground(STOP_FOREGROUND_REMOVE)
            postFinalNotification(finalResult)
            ProcessingState.job = null
            running = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Копия архива в кэше приложения: через FileProvider её надёжно принимает Gmail. */
    private fun prepareShareUri(r: ProcessResult.Success): Uri? {
        val dir = File(cacheDir, "share")
        dir.deleteRecursively()
        dir.mkdirs()
        val f = File(dir, r.zipName)
        val input = contentResolver.openInputStream(r.zipUri) ?: return null
        input.use { inp -> f.outputStream().use { inp.copyTo(it) } }
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
    }

    private fun fgsType(): Int =
        if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CH_PROGRESS, "Обработка", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_DONE, "Результат", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    private fun appIntent(requestCode: Int, send: Boolean): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (send) intent.putExtra(MainActivity.EXTRA_SEND, true)
        return PendingIntent.getActivity(
            this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun progressNotification(text: String, cur: Int, total: Int): Notification =
        NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Persistor: обработка")
            .setContentText(text)
            .setProgress(total, cur, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(appIntent(0, false))
            .build()

    private fun postFinalNotification(r: ProcessResult) {
        val b = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(appIntent(0, false))
            .setAutoCancel(true)
        when (r) {
            is ProcessResult.Success -> {
                b.setContentTitle("Готово: ${r.zipName}")
                b.setContentText("${mb(r.zipSize)}, файлов: ${r.files}")
                // Кнопка открывает приложение, а оно уже запускает Gmail и записывает это в статистику
                if (r.shareUri != null) b.addAction(0, "Отправить в Gmail", appIntent(1, true))
            }
            is ProcessResult.Failure -> {
                b.setContentTitle("Ошибка обработки")
                b.setContentText(r.reason)
                b.setStyle(NotificationCompat.BigTextStyle().bigText(r.reason))
            }
        }
        nm.notify(ID_RESULT, b.build())
    }

    companion object {
        private const val CH_PROGRESS = "persistor_progress"
        private const val CH_DONE = "persistor_done"
        private const val ID_PROGRESS = 1
        private const val ID_RESULT = 2
    }
}
