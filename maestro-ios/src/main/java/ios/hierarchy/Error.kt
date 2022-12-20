package ios.hierarchy

import com.fasterxml.jackson.annotation.JsonProperty

internal data class Error(
    @JsonProperty("errorMessage") val errorMessage: String,
    @JsonProperty("errorCode") val errorCode: String,
)