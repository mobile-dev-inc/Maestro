package conductor.orchestra

class ScrollCommand {

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
}

class BackPressCommand {

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
}

data class TapOnElementCommand(val selector: ElementSelector, val retryIfNoChange: Boolean? = null)

data class AssertCommand(
    val visible: ElementSelector? = null,
)

data class InputTextCommand(
    val text: String
)
