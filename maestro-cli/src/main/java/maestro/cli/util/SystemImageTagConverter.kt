package maestro.cli.util

import maestro.device.SystemImageTag
import picocli.CommandLine

/**
 * Turns a `--android-system-image` argument into a [SystemImageTag].
 *
 * Mirrors `maestro.cli.report.ReportFormat.Converter`, but lives here rather than nested in
 * the enum: [SystemImageTag] is declared in `maestro-client`, which has no picocli dependency
 * and should not gain one for a CLI concern.
 *
 * Accepts only the SDK-canonical tag strings (`google_apis`, `google_apis_playstore`) — the
 * `@JsonValue` of each constant — so the CLI vocabulary and the wire vocabulary stay identical.
 */
class SystemImageTagConverter : CommandLine.ITypeConverter<SystemImageTag> {
    override fun convert(value: String): SystemImageTag = SystemImageTag.fromString(value)
}

/**
 * The values `--android-system-image` accepts, for `${COMPLETION-CANDIDATES}` and shell
 * completion.
 *
 * Picocli's built-in enum candidates are the Kotlin constant names (`GOOGLE_APIS`), which
 * [SystemImageTagConverter] rejects — so help output would advertise values that fail. This
 * lists the SDK-canonical tag strings instead.
 */
class SystemImageTagCandidates : Iterable<String> {
    override fun iterator(): Iterator<String> =
        SystemImageTag.entries.map { it.value }.iterator()
}
