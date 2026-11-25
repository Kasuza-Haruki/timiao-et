package app.timiao.et.split

import android.content.Context
import android.content.SharedPreferences

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

    /**
     * Calls [onChanged] when any network's split policy is saved. SharedPreferences keeps
     * only a weak reference, so the caller must keep [onChanged]'s returned listener.
     */
    fun observe(onChanged: () -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null && key.startsWith(KEY_PREFIX)) onChanged()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun stopObserving(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        fun policy(context: Context, networkId: String?): SplitPolicy =
            SplitStore(context).load(networkId)

        /** One policy for the whole device: all networks share one TUN, so one rule set. */
        const val GLOBAL_ID = "global"

        private const val PREFS = "split_policy"
        private const val KEY_PREFIX = "network_"
    }
}
