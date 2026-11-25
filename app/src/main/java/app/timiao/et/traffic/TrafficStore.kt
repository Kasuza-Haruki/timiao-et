package app.timiao.et.traffic

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.util.Base64

/**
 * Tiny line-based store for traffic counters. Not JSON: it stays human-readable and
 * needs no serialization plugin. Lines that do not parse are ignored, so a damaged
 * file never crashes the screen.
 *
 * ```
 * v1
 * policy,<block 0|1>,<warn MB>
 * baseline,<base64url network id>,<tx>,<rx>
 * day,<ISO date>,<WIFI|MOBILE|OTHER>,<tx>,<rx>
 * ```
 */
class TrafficStore(private val file: File) {

    @Synchronized
    fun load(): TrafficData {
        if (!file.isFile) return TrafficData()
        val lines = runCatching { file.readLines() }.getOrElse { return TrafficData() }
        var policy = TrafficPolicy()
        val days = mutableMapOf<LocalDate, MutableMap<NetworkKind, BytePair>>()
        val baselines = mutableMapOf<String, BytePair>()
        lines.forEach { line ->
            val parts = line.split(',')
            when {
                parts.firstOrNull() == "policy" && parts.size >= 3 -> {
                    policy = TrafficPolicy(
                        blockOnMobile = parts[1] == "1",
                        warnAtMb = parts[2].toIntOrNull()?.coerceAtLeast(0) ?: 0,
                    )
                }
                parts.firstOrNull() == "baseline" && parts.size >= 4 -> {
                    val id = decodeId(parts[1]) ?: return@forEach
                    val tx = parts[2].toLongOrNull() ?: return@forEach
                    val rx = parts[3].toLongOrNull() ?: return@forEach
                    baselines[id] = BytePair(tx, rx)
                }
                parts.firstOrNull() == "day" && parts.size >= 5 -> {
                    val date = runCatching { LocalDate.parse(parts[1]) }.getOrNull() ?: return@forEach
                    val kind = runCatching { NetworkKind.valueOf(parts[2]) }.getOrNull() ?: return@forEach
                    val tx = parts[3].toLongOrNull() ?: return@forEach
                    val rx = parts[4].toLongOrNull() ?: return@forEach
                    days.getOrPut(date) { mutableMapOf() }[kind] = BytePair(tx, rx)
                }
            }
        }
        return TrafficData(days = days, baselines = baselines, policy = policy)
    }

    @Synchronized
    fun save(data: TrafficData) {
        val text = buildString {
            appendLine("v1")
            appendLine("policy,${if (data.policy.blockOnMobile) 1 else 0},${data.policy.warnAtMb}")
            data.baselines.forEach { (id, bytes) ->
                appendLine("baseline,${encodeId(id)},${bytes.tx},${bytes.rx}")
            }
            data.days.toSortedMap().forEach { (date, kinds) ->
                kinds.forEach { (kind, bytes) ->
                    appendLine("day,$date,${kind.name},${bytes.tx},${bytes.rx}")
                }
            }
        }
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(text, StandardCharsets.UTF_8)
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.onFailure {
            // Worst case: keep the old file. Losing one save is better than crashing.
        }
    }

    private fun encodeId(id: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray(StandardCharsets.UTF_8))

    private fun decodeId(text: String): String? = runCatching {
        String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8)
    }.getOrNull()
}
