package app.timiao.et.split

import android.content.Context

/** Stores one [SplitPolicy] per network id in plain SharedPreferences. */
class SplitStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(networkId: String?): SplitPolicy {
        if (networkId.isNullOrBlank()) return SplitPolicy()
        return SplitPolicyCodec.decode(prefs.getString(KEY_PREFIX + networkId, null))
    }

    fun save(networkId: String, policy: SplitPolicy) {
        prefs.edit()
            .putString(KEY_PREFIX + networkId, SplitPolicyCodec.encode(policy))
            .apply()
    }

    companion object {
        fun policy(context: Context, networkId: String?): SplitPolicy =
            SplitStore(context).load(networkId)

        private const val PREFS = "split_policy"
        private const val KEY_PREFIX = "network_"
    }
}
