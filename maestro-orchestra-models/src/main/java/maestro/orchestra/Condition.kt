package maestro.orchestra

data class Condition(
    val visible: ElementSelector? = null,
    val notVisible: ElementSelector? = null,
) {

    fun injectEnv(env: Map<String, String>): Condition {
        return copy(
            visible = visible?.injectEnv(env),
            notVisible = notVisible?.injectEnv(env),
        )
    }

    fun description(): String {
        val descriptions = mutableListOf<String>()

        visible?.let {
            descriptions.add("${it.description()} is visible")
        }

        notVisible?.let {
            descriptions.add("${it.description()} is not visible")
        }

        return if (descriptions.isEmpty()) {
            "true"
        } else {
            descriptions.joinToString(" and ")
        }
    }

}
