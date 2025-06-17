package maestro

sealed class OnDeviceElementQuery {

    data class Css(
        val css: String,
    ) : OnDeviceElementQuery()

}