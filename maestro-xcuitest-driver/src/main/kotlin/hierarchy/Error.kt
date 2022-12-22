package hierarchy

import com.fasterxml.jackson.annotation.JsonProperty

data class Error(
    @JsonProperty("errorMessage") val errorMessage: String,
    @JsonProperty("errorCode") val errorCode: String,
)