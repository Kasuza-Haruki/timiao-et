package app.timiao.et.update

import android.content.Context

/** Reads the pinned EasyTier tag from assets or a BuildConfig value supplied by the wiring. */
public object KernelTag {
    public const val ASSET_NAME: String = "kernel_tag.txt"
    public const val FALLBACK: String = "v2.7.1"

    /** First non-empty, non-comment line. Unit-testable. */
    public fun parse(raw: String?): String? = raw
        ?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }

    public fun read(context: Context, buildConfigTag: String? = null): String {
        parse(buildConfigTag)?.let { return it }
        val fromAssets = runCatching {
            context.assets.open(ASSET_NAME).bufferedReader().use { parse(it.readText()) }
        }.getOrNull()
        return fromAssets ?: FALLBACK
    }
}
