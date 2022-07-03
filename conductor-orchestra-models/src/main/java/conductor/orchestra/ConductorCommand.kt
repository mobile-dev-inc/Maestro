package conductor.orchestra

data class ConductorCommand(
    val tapOnElement: TapOnElementCommand? = null,
    val scrollCommand: ScrollCommand? = null,
    val backPressCommand: BackPressCommand? = null,
    val assertCommand: AssertCommand? = null,
    val inputTextCommand: InputTextCommand? = null,
    val launchAppCommand: LaunchAppCommand? = null,
) {

    fun description(): String {
        tapOnElement?.let {
            return it.description()
        }

        scrollCommand?.let {
            return it.description()
        }

        backPressCommand?.let {
            return it.description()
        }

        assertCommand?.let {
            return it.description()
        }

        inputTextCommand?.let {
            return it.description()
        }

        launchAppCommand?.let {
            return it.description()
        }

        return "No op"
    }

}
