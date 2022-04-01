package conductor

interface Crawler {
    fun crawl(
        conductor: Conductor,
        maxActions: Int,
    )
}
