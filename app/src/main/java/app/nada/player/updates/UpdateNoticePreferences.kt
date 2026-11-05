package app.nada.player.updates

import android.content.Context

internal class UpdateNoticePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("update_notice", Context.MODE_PRIVATE)
    val policy = UpdateNoticePolicy(preferences.getString("snoozed_version", null), preferences.getLong("snoozed_until", 0))

    fun snooze(version: String, now: Long) {
        val until = policy.snooze(version, now)
        preferences.edit().putString("snoozed_version", version).putLong("snoozed_until", until).apply()
    }
}
