package app.timiao.et.service

import android.content.Context

/** Remembers the last connected network so the QS tile can reconnect it. */
object ServiceState {
    private const val FILE = "service_state"
    private const val KEY_LAST = "last_network"

    fun setLast(ctx: Context, id: String) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_LAST, id).apply()

    fun last(ctx: Context): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_LAST, null)
}
