package app.timiao.et.core

/** Text resolver for the few core-generated messages the app shows. */
public interface CoreStrings {
    public fun get(key: String, vararg args: Any?): String
}

public object ChineseCoreStrings : CoreStrings {
    override fun get(key: String, vararg args: Any?): String = when (key) {
        "core_overlap_message" -> String.format(
            java.util.Locale.US,
            "与正在运行的「%1\$s」网段重叠（%2\$s），请先断开它或修改网段",
            *args,
        )
        else -> key
    }
}
