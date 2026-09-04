package maestro.js

/**
 * Per-request overrides for the JS `http` binding.
 *
 * Every option resolves through two layers, in order:
 *  1. the request's own params map — `http.post(url, { timeout: 900000 })`
 *  2. the flow env — `MAESTRO_JS_HTTP_TIMEOUT=900000`
 *
 * Shell `MAESTRO_*` vars are copied into the flow env (see
 * `maestro.orchestra.util.Env.withInjectedShellEnvVars`), which also carries
 * `--env` values and a flow's own `config: env:` block, and which is uploaded
 * with cloud runs.
 *
 * [DEFAULT] means "change nothing", so the caller can keep using the shared
 * client instead of deriving a new one.
 */
data class JsHttpOptions(
    val timeoutMs: Long? = null,
) {

    companion object {
        val DEFAULT = JsHttpOptions()

        fun resolve(params: Map<String, Any>?, env: Map<String, String>): JsHttpOptions =
            JsHttpOptions(
                timeoutMs = positiveMillis(params?.get("timeout"), "`timeout` param")
                    ?: positiveMillis(env["MAESTRO_JS_HTTP_TIMEOUT"], "MAESTRO_JS_HTTP_TIMEOUT"),
            )

        /**
         * Accepts a JS number or a numeric string — env values are always strings.
         * A value that is present but unusable throws rather than silently falling
         * back to the default, which would leave the flow behaving as though the
         * override had been applied.
         */
        private fun positiveMillis(value: Any?, source: String): Long? {
            if (value == null) return null

            val millis = when (value) {
                is Number -> value.toLong()
                is CharSequence -> value.toString().trim().let {
                    if (it.isEmpty()) return null
                    it.toLongOrNull()
                        ?: throw IllegalArgumentException(
                            "$source must be a whole number of milliseconds, but was \"$it\""
                        )
                }
                else -> throw IllegalArgumentException(
                    "$source must be a whole number of milliseconds, but was ${value.javaClass.simpleName}"
                )
            }

            if (millis <= 0) {
                throw IllegalArgumentException(
                    "$source must be a positive number of milliseconds, but was $millis"
                )
            }

            return millis
        }
    }
}
