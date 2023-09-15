package xcuitest.api

import com.fasterxml.jackson.annotation.JsonProperty

data class Error(
    @JsonProperty("errorMessage") val errorMessage: String,
    @JsonProperty("code") val errorCode: String,
)