package app.timiao.et.auto

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class AutoRulesState(
    val enabled: Boolean = false,
    val rules: List<AutoRule> = emptyList(),
)

/**
 * SharedPreferences-backed rule store, one process-wide instance so the UI and
 * the broadcast receivers always see the same [StateFlow].
 */
class AutoRulesStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AutoRulesState> = _state.asStateFlow()

    val rules: List<AutoRule> get() = _state.value.rules

    fun setEnabled(enabled: Boolean) = write(_state.value.copy(enabled = enabled))

    fun ruleFor(networkId: String?): AutoRule? =
        _state.value.rules.firstOrNull { it.networkId == networkId }

    /** Adds or replaces the rule for the same target (null = global). Order is kept. */
    fun upsert(rule: AutoRule) {
        val list = _state.value.rules.toMutableList()
        val index = list.indexOfFirst { it.networkId == rule.networkId }
        if (index >= 0) list[index] = rule else list.add(rule)
        write(_state.value.copy(rules = list))
    }

    fun remove(networkId: String?) {
        write(_state.value.copy(rules = _state.value.rules.filterNot { it.networkId == networkId }))
    }

    private fun write(state: AutoRulesState) {
        prefs.edit().putString(KEY, encode(state)).apply()
        _state.value = state
    }

    private fun read(): AutoRulesState = runCatching {
        val raw = prefs.getString(KEY, null) ?: return@runCatching AutoRulesState()
        val obj = JSONObject(raw)
        val array = obj.optJSONArray("rules") ?: JSONArray()
        val rules = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val networkId = if (item.isNull("networkId")) null else item.optString("networkId")
                val names = item.optJSONArray("wifiNames")?.let { names ->
                    List(names.length()) { names.optString(it) }
                } ?: emptyList()
                add(
                    AutoRule(
                        networkId = networkId,
                        condition = AutoCondition.fromKey(item.optString("condition")),
                        wifiNames = names,
                        keepNotification = item.optBoolean("keepNotification", false),
                        enabled = item.optBoolean("enabled", true),
                    ),
                )
            }
        }.distinctBy { it.networkId }
        AutoRulesState(enabled = obj.optBoolean("enabled", false), rules = rules)
    }.getOrDefault(AutoRulesState())

    private fun encode(state: AutoRulesState): String {
        val array = JSONArray()
        state.rules.forEach { rule ->
            array.put(
                JSONObject().apply {
                    put("networkId", rule.networkId ?: JSONObject.NULL)
                    put("condition", rule.condition.key)
                    put("wifiNames", JSONArray(rule.wifiNames))
                    put("keepNotification", rule.keepNotification)
                    put("enabled", rule.enabled)
                },
            )
        }
        return JSONObject().apply {
            put("enabled", state.enabled)
            put("rules", array)
        }.toString()
    }

    companion object {
        private const val PREFS = "auto_rules"
        private const val KEY = "state"

        @Volatile private var instance: AutoRulesStore? = null

        fun get(context: Context): AutoRulesStore =
            instance ?: synchronized(this) {
                instance ?: AutoRulesStore(context.applicationContext).also { instance = it }
            }
    }
}
