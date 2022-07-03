package conductor.orchestra

interface Command {

    fun description(): String

}

class ScrollCommand : Command {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        return true
    }

    override fun hashCode(): Int {
        return javaClass.hashCode()
    }

    override fun toString(): String {
        return "ScrollCommand()"
    }

    override fun description(): String {
        return "Scroll vertically"
    }

}

class BackPressCommand : Command {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        return true
    }

    override fun hashCode(): Int {
        return javaClass.hashCode()
    }

    override fun toString(): String {
        return "BackPressCommand()"
    }

    override fun description(): String {
        return "Press back"
    }

}

data class TapOnElementCommand(
    val selector: ElementSelector,
    val retryIfNoChange: Boolean? = null
) : Command {

    override fun description(): String {
        return "Tap on: ${selector.description()}"
    }

}

data class AssertCommand(
    val visible: ElementSelector? = null,
) : Command {

    override fun description(): String {
        if (visible != null) {
            return "Assert visible: ${visible.description()}"
        }

        return "No op"
    }

}

data class InputTextCommand(
    val text: String
) : Command {

    override fun description(): String {
        return "Input text: $text"
    }

}

data class LaunchAppCommand(
    val appId: String,
) : Command {

    override fun description(): String {
        return "Launch app: $appId"
    }

}
