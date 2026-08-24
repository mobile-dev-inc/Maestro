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
