package app.timiao.et.core.diag

/**
 * Runs all checks and returns one secret-free report.
 * [probe] and [clock] are injectable so tests stay offline and deterministic.
 */
public class DiagnosticsEngine(
    private val probe: PeerReachabilityProbe = DefaultPeerReachabilityProbe,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    public suspend fun run(input: DiagnosticInput, device: DeviceProbe? = null): DiagnosticReport {
        val config = input.config
        val snapshot = EventParser.parse(input.rawInfosJson, config?.instanceName)
        val items = mutableListOf<DiagItem>()

        items += ConfigChecks.run(config)
        if (config != null) {
            ReachabilityCheck.run(config, probe)?.let { items += it }
            if (input.status != null) {
                items += StatusChecks.run(config, input.status, snapshot)
            } else {
                items += DiagItem(
                    id = "status_missing",
                    title = "运行状态",
                    severity = DiagSeverity.Warn,
                    summary = "没有拿到运行状态",
                    advice = "先启动网络并等几秒，再点『重新诊断』。",
                )
            }
        }
        if (device != null) items += DeviceChecks.run(device)
        items += EventChecks.run(snapshot)

        val secret = config?.networkSecret.orEmpty()
        return DiagnosticReport(
            items = items.map { it.redacted(secret) },
            generatedAtEpochMs = clock(),
        )
    }
}
