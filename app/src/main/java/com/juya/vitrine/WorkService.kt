package com.juya.vitrine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** 사진·동영상을 만드는 동안 앱에서 나가도 꺼지지 않게 붙잡아 두는 서비스 (알림에 진행 표시) */
class WorkService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra("text") ?: "…"
        val n = progress(this, text)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
        return START_NOT_STICKY
    }

    companion object {
        const val ID = 41
        const val CH_WORK = "work"
        const val CH_DONE = "done"

        fun channels(c: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_WORK, "Vitrine AI – 만드는 중", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_DONE, "Vitrine AI – 완성", NotificationManager.IMPORTANCE_HIGH))
        }

        private fun openApp(c: Context): PendingIntent =
            PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun progress(c: Context, text: String): Notification =
            NotificationCompat.Builder(c, CH_WORK)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Vitrine AI")
                .setContentText(text)
                .setOngoing(true).setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
                .setContentIntent(openApp(c))
                .build()

        fun done(c: Context, title: String, text: String): Notification =
            NotificationCompat.Builder(c, CH_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(openApp(c))
                .build()
    }
}
