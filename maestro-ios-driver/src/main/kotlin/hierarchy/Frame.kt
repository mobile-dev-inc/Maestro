package hierarchy

import com.fasterxml.jackson.annotation.JsonProperty

data class Frame(
    @JsonProperty("Width") val width: Float,
    @JsonProperty("Height") val height: Float,
    @JsonProperty("Y") val y: Float,
    @JsonProperty("X") val x: Float
)