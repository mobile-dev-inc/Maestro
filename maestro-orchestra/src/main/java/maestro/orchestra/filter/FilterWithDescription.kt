package maestro.orchestra.filter

import maestro.ElementFilter

data class FilterWithDescription(
    val description: String,
    val filterFunc: ElementFilter,
)
