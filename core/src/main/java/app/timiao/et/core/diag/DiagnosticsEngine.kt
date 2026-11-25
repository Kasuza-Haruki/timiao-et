package app.timiao.et.core.diag

/**
 * Runs all checks and returns one secret-free report.
 * [probe], [clock] and [strings] are injectable so tests stay offline, deterministic and
 * free of Android resources; the app supplies a resource-backed [DiagStrings].
 */
public class DiagnosticsEngine(
    private val probe: PeerReachabilityProbe = DefaultPeerReachabilityProbe,
    private val clock: () -> Long = System::currentTimeMillis,
    private val strings: DiagStrings = ChineseDiagStrings,
) {
    public suspend fun run(input: DiagnosticInput, device: DeviceProbe? = null): DiagnosticReport {
        val config = input.config
        val snapshot = EventParser.parse(input.rawInfosJson, config?.instanceName, strings)
        val items = mutableListOf<DiagItem>()

        items += ConfigChecks.run(config, strings)
        if (config != null) {
            ReachabilityCheck.run(config, probe, strings)?.let { items += it }
            if (input.status != null) {
                items += StatusChecks.run(config, input.status, snapshot, strings)
            } else {
                items += DiagItem(
                    id = "status_missing",
                    title = strings.get("diag_status_missing_title"),
                    severity = DiagSeverity.Warn,
                    summary = strings.get("diag_status_missing_summary"),
                    advice = strings.get("diag_status_missing_advice"),
                )
            }
        }
        if (device != null) items += DeviceChecks.run(device, strings)
        items += EventChecks.run(snapshot, strings)

        val secret = config?.networkSecret.orEmpty()
        return DiagnosticReport(
            items = items.map { it.redacted(secret) },
            generatedAtEpochMs = clock(),
        )
    }
}
