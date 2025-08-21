package maestro.js

import org.graalvm.polyglot.HostAccess

interface GraalHostAccessible {
    fun configureHostAccess(builder: HostAccess.Builder)
}

class GraalHostAccessBuilder(private val bindings: List<GraalHostAccessible>) {
    fun build(): HostAccess {
        val builder = HostAccess.newBuilder()
        bindings.forEach { it.configureHostAccess(builder) }
        return builder.build()
    }
}