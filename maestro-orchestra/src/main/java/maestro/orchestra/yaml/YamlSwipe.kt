package maestro.orchestra.yaml

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.TreeNode
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import maestro.SwipeDirection
import java.lang.IllegalArgumentException

@JsonDeserialize(using = YamlSwipeDeserializer::class)
interface YamlSwipe {
    val duration: Long
}

data class YamlSwipeDirection(val direction: SwipeDirection, override val duration: Long = DEFAULT_DURATION_IN_MILLIS) : YamlSwipe
data class YamlCoordinateSwipe(val start: String, val end: String, override val duration: Long = DEFAULT_DURATION_IN_MILLIS) : YamlSwipe

private const val DEFAULT_DURATION_IN_MILLIS = 2000L

class YamlSwipeDeserializer: JsonDeserializer<YamlSwipe>() {

    override fun deserialize(parser: JsonParser, ctxt: DeserializationContext): YamlSwipe {
        val mapper = parser.codec as ObjectMapper
        val root: TreeNode = mapper.readTree(parser)
        val input = root.fieldNames().asSequence().toList()
        val duration = getDuration(root)
        val isDirectionalSwipe = input == listOf("direction", "duration") || input == listOf("direction")
        val isCoordinateSwipe = input == listOf("start", "end", "duration") || input == listOf("start", "end")
        return when {
            isDirectionalSwipe ->  {
                val direction = root.get("direction").toString().replace("\"", "")
                val swipeDirection = runCatching { SwipeDirection.valueOf(direction) }.onFailure {
                    if (it is IllegalArgumentException) {
                        throw IllegalArgumentException("You provided an incorrect value for \"direction\": $direction, expected directions: \"RIGHT\", \"LEFT\", \"UP\", or \"DOWN\".")
                    }
                }.getOrThrow()

                YamlSwipeDirection(swipeDirection, duration)
            }
            isCoordinateSwipe -> {
                YamlCoordinateSwipe(
                    root.path("start").toString().replace("\"", ""),
                    root.path("end").toString().replace("\"", ""),
                    duration
                )
            }
            input == listOf("start", "duration") || input == listOf("start") -> {
                throw IllegalArgumentException("You only provided a start coordinate for the swipe command. " +
                    "Please specify an end coordinate as well.")
            }
            input == listOf("end", "duration") || input == listOf("end") -> {
                throw IllegalArgumentException("You only provided an end coordinate for the swipe command. " +
                    "Please specify a start coordinate as well.")
            }
            else -> {
                throw IllegalArgumentException(
                    "Swipe command takes either: \n" +
                        "\t1. direction: Direction based swipe with: \"RIGHT\", \"LEFT\", \"UP\", or \"DOWN\" or \n" +
                        "\t2. start and end: Coordinates based swipe with: \"start\" and \"end\" coordinates \n" +
                        "It seems you provided invalid input with: $input"
                )
            }
        }
     }

    private fun getDuration(root: TreeNode): Long {
        return if (root.path("duration").isMissingNode) {
            DEFAULT_DURATION_IN_MILLIS
        } else {
            root.path("duration").toString().replace("\"", "").toLong()
        }
    }

}