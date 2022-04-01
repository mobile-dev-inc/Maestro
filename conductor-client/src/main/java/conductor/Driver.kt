package conductor

interface Driver {

    fun open()

    fun close()

    fun deviceInfo(): DeviceInfo

    fun tap(point: Point)

    fun contentDescriptor(): TreeNode

    fun scrollVertical()

    fun backPress()
}
