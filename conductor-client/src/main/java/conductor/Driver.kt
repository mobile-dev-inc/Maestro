package conductor

interface Driver {

    fun name(): String

    fun open()

    fun close()

    fun deviceInfo(): DeviceInfo

    fun tap(point: Point)

    fun contentDescriptor(): TreeNode

    fun scrollVertical()

    fun backPress()

    fun inputText(text: String)
}
