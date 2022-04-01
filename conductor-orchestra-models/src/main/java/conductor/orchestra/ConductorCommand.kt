package conductor.orchestra

data class ConductorCommand(
    val tapOnElement: TapOnElementCommand? = null,
    val scrollCommand: ScrollCommand? = null,
    val backPressCommand: BackPressCommand? = null,
    val crawlerCommand: CrawlerCommand? = null,
)
