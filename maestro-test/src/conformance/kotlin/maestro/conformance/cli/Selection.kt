package maestro.conformance.cli

object Selection {
    private val SUPPORTED = 24..36

    fun parseApis(spec: String): List<Int> {
        val out = sortedSetOf<Int>()
        for (part in spec.split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
            if (part.contains("..")) {
                val (lo, hi) = part.split("..").map { it.trim().toInt() }
                (lo..hi).forEach { if (it in SUPPORTED) out += it }
            } else {
                val v = part.toInt()
                if (v in SUPPORTED) out += v
            }
        }
        return out.toList()
    }

    fun parseList(spec: String): List<String> =
        spec.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}
