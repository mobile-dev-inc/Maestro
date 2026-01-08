package maestro.utils

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

object HttpUtils {

    fun Map<*, *>.toMultipartBody(scriptDir: File? = null): MultipartBody {
        return MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addAllFormDataParts(this, scriptDir)
            .build()
    }

    private fun <T : Map<*, *>> MultipartBody.Builder.addAllFormDataParts(multipartForm: T?, scriptDir: File?): MultipartBody.Builder {
        multipartForm?.forEach { (key, value) ->
            val filePath = (value as? Map<*, *> ?: emptyMap<Any, Any>())["filePath"]
            if (filePath != null) {
                val file = resolveFilePath(filePath.toString(), scriptDir)
                val mediaType = (value as? Map<*, *> ?: emptyMap<Any, Any>())["mediaType"].toString()
                this.addFormDataPart(key.toString(), file.name, file.asRequestBody(mediaType.toMediaTypeOrNull()))
            } else {
                this.addFormDataPart(key.toString(), value.toString())
            }
        }
        return this
    }

    private fun resolveFilePath(filePath: String, scriptDir: File?): File {
        val file = File(filePath)

        // If the file path is absolute and exists, use it directly
        if (file.isAbsolute && file.exists()) {
            return file
        }

        // If we have a workspace root, try to resolve relative to it
//        if (scriptDir != null) {
//            val resolvedFile = File(scriptDir, filePath)
//            if (resolvedFile.exists()) {
//                return resolvedFile
//            }
//        }

        // Fall back to the original behavior (current working directory)
        return file
    }
}
