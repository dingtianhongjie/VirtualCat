package com.example.virtualcat // ←パッケージ名はプロジェクトに合わせてください

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat

class CatAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 端末の再起動時だけスケジュールを再セットする
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            CatScheduler.scheduleNotifications(context)
            return
        }

        // 日付変更時の定期更新用アクションならスケジュールを組み直す
        if (intent.action == "com.example.virtualcat.ACTION_REFRESH_SCHEDULE") {
            CatScheduler.scheduleNotifications(context)
            return
        }

        val prefs = context.getSharedPreferences("cat_prefs", Context.MODE_PRIVATE)
        val catName = prefs.getString("cat_name", "たま") ?: "たま"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "cat_channel",
                "ネコの鳴き声",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val randomVoice = CAT_VOICES.random()
        val catIconBitmap = CatIconHelper.loadCurrentCatBitmap(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_MINI_ROOM", true)
            putExtra("CAT_MESSAGE", randomVoice)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            System.currentTimeMillis().toInt(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val owner = Person.Builder().setName("飼い主").build()
        val catPerson = Person.Builder()
            .setName(catName)
            .setIcon(IconCompat.createWithBitmap(catIconBitmap))
            .build()

        val messagingStyle = NotificationCompat.MessagingStyle(owner)
            .addMessage(randomVoice, System.currentTimeMillis(), catPerson)

        val builder = NotificationCompat.Builder(context, "cat_channel")
            .setSmallIcon(R.drawable.ic_stat_cat)
            .setStyle(messagingStyle)
            .setContentIntent(contentPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        try {
            val notificationId = System.currentTimeMillis().toInt()
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
}