package maestro.utils

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

object HttpUtils {

    fun Map<*, *>.toMultipartBody(scriptDir: File? = null, scope: FileAccessScope = FileAccessScope.everything): MultipartBody {
        return MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addAllFormDataParts(this, scriptDir, scope)
            .build()
    }

    private fun <T : Map<*, *>> MultipartBody.Builder.addAllFormDataParts(
        multipartForm: T?,
        scriptDir: File?,
        scope: FileAccessScope,
    ): MultipartBody.Builder {
        multipartForm?.forEach { (key, value) ->
            val filePath = (value as? Map<*, *> ?: emptyMap<Any, Any>())["filePath"]
            if (filePath != null) {
                val file = resolveFilePath(filePath.toString(), scriptDir, scope)
                val mediaType = (value as? Map<*, *> ?: emptyMap<Any, Any>())["mediaType"].toString()
                this.addFormDataPart(key.toString(), file.name, file.asRequestBody(mediaType.toMediaTypeOrNull()))
            } else {
                this.addFormDataPart(key.toString(), value.toString())
            }
        }
        return this
    }

    private fun resolveFilePath(filePath: String, scriptDir: File?, scope: FileAccessScope): File {
        @Suppress("ForbiddenMethodCall") // Default anchor directory, not the flow file path resolved through FileAccessScope.
        val anchor = (scriptDir ?: File(".")).toPath()
        return scope.resolve(anchor, filePath).toFile()
    }
}
