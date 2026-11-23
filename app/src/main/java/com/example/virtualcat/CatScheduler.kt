package com.example.virtualcat // ←パッケージ名はプロジェクトに合わせてください

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar
import kotlin.math.abs

object CatScheduler {

    private const val MAX_SLOTS = 40
    private const val REFRESH_REQUEST_CODE = 888

    fun scheduleNotifications(context: Context) {
        val prefs = context.getSharedPreferences("cat_prefs", Context.MODE_PRIVATE)
        val dailyCount = prefs.getInt("daily_count", 5)
        val sleepStartHour = prefs.getInt("sleep_start_hour", 23)
        val sleepStartMinute = prefs.getInt("sleep_start_minute", 0)
        val sleepEndHour = prefs.getInt("sleep_end_hour", 7)
        val sleepEndMinute = prefs.getInt("sleep_end_minute", 0)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        cancelAll(context, alarmManager)

        val sleepStart = sleepStartHour * 60 + sleepStartMinute
        val sleepEnd = sleepEndHour * 60 + sleepEndMinute

        val awakeMinutes = (0 until 1440).filter { minute ->
            isAwake(minute, sleepStart, sleepEnd)
        }
        if (awakeMinutes.isEmpty()) return

        // 1日の起きている時間と通知回数から、適切な最小間隔（分）を計算（最大45分）
        val minGapMinutes = (awakeMinutes.size / (dailyCount + 1)).coerceIn(15, 45)

        val now = Calendar.getInstance()
        val currentMinuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        val targetTimestamps = mutableListOf<Long>()

        // 1. 今日の残り時間分（最低15分後から開始、残り時間が少なければ0回でもOKにする）
        val todayAwake = awakeMinutes.filter { it > currentMinuteOfDay + 15 }
        if (todayAwake.isNotEmpty()) {
            val ratio = todayAwake.size.toDouble() / awakeMinutes.size.toDouble()
            val todayCount = Math.round(dailyCount * ratio).toInt().coerceAtMost(todayAwake.size)

            pickSpacedMinutes(todayAwake, todayCount, minGapMinutes).forEach { minuteOfDay ->
                targetTimestamps.add(toTimestamp(now, dayOffset = 0, minuteOfDay = minuteOfDay))
            }
        }

        // 2. 明日・明後日分をまとめて予約しておく
        for (dayOffset in 1..2) {
            val count = dailyCount.coerceAtMost(awakeMinutes.size)
            pickSpacedMinutes(awakeMinutes, count, minGapMinutes).forEach { minuteOfDay ->
                targetTimestamps.add(toTimestamp(now, dayOffset = dayOffset, minuteOfDay = minuteOfDay))
            }
        }

        // タイムスタンプ順に最大MAX_SLOTS件まで予約
        targetTimestamps.sorted().take(MAX_SLOTS).forEachIndexed { index, triggerTime ->
            val intent = Intent(context, CatAlarmReceiver::class.java).apply {
                putExtra("ALARM_ID", index)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                index,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }

        // 3. 翌日の起床時間ごろにスケジュールを自動補充する定期アラームをセット
        val refreshIntent = Intent(context, CatAlarmReceiver::class.java).apply {
            action = "com.example.virtualcat.ACTION_REFRESH_SCHEDULE"
        }
        val refreshPendingIntent = PendingIntent.getBroadcast(
            context,
            REFRESH_REQUEST_CODE,
            refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextRefreshTime = toTimestamp(now, dayOffset = 1, minuteOfDay = sleepEnd)
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            nextRefreshTime,
            refreshPendingIntent
        )
    }

    // 指定した最小間隔（minGapMinutes）以上離れるようにランダムな時刻を選ぶ
    private fun pickSpacedMinutes(
        candidates: List<Int>,
        targetCount: Int,
        minGapMinutes: Int
    ): List<Int> {
        if (targetCount <= 0 || candidates.isEmpty()) return emptyList()

        val chosen = mutableListOf<Int>()
        for (candidate in candidates.shuffled()) {
            if (chosen.size >= targetCount) break
            val isFarEnough = chosen.all { existing ->
                val diff = abs(existing - candidate)
                minOf(diff, 1440 - diff) >= minGapMinutes
            }
            if (isFarEnough) {
                chosen.add(candidate)
            }
        }
        return chosen.sorted()
    }

    private fun cancelAll(context: Context, alarmManager: AlarmManager) {
        for (i in 0 until MAX_SLOTS) {
            val intent = Intent(context, CatAlarmReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                i,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            }
        }
    }

    private fun isAwake(minute: Int, sleepStart: Int, sleepEnd: Int): Boolean {
        return when {
            sleepStart < sleepEnd -> minute !in sleepStart until sleepEnd
            sleepStart > sleepEnd -> minute in sleepEnd until sleepStart
            else -> true
        }
    }

    private fun toTimestamp(base: Calendar, dayOffset: Int, minuteOfDay: Int): Long {
        return (base.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
            set(Calendar.SECOND, (0..59).random())
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}