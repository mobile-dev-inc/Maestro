package maestro.cli.driver

class Spinner(private val message: String = "Processing") {
    private val frames = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
    private var active = true
    private lateinit var thread: Thread

    fun start() {
        thread = Thread {
            var i = 0
            while (active) {
                print("\r${frames[i % frames.size]} $message")
                Thread.sleep(100)
                i++
            }
        }
        thread.start()
    }

    fun stop() {
        active = false
        thread.join()
        print("\r✅ $message\n")
    }
}