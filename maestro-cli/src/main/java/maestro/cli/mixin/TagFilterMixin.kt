package maestro.cli.mixin

import picocli.CommandLine

class TagFilterMixin {
    @CommandLine.Option(
        names = ["--include-tags"],
        description = ["List of tags that will remove the Flows that does not have the provided tags"],
        split = ",",
    )
    var includeTags: List<String> = emptyList()

    @CommandLine.Option(
        names = ["--exclude-tags"],
        description = ["List of tags that will remove the Flows containing the provided tags"],
        split = ",",
    )
    var excludeTags: List<String> = emptyList()
}
