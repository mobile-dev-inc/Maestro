package maestro.utils

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

object HttpUtils {

    fun Map<*, *>.toMultipartBody(): MultipartBody {
        return MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addAllFormDataParts(this)
            .build()
    }

    private fun <T : Map<*, *>> MultipartBody.Builder.addAllFormDataParts(multipartForm: T?): MultipartBody.Builder {
        multipartForm?.forEach { (key, value) ->
            val filePath = (value as? Map<*, *> ?: emptyMap<Any, Any>())["filePath"]
            if (filePath != null) {
                val file = File(filePath.toString())
                val mediaType = (value as? Map<*, *> ?: emptyMap<Any, Any>())["mediaType"].toString()
                this.addFormDataPart(key.toString(), file.name, file.asRequestBody(mediaType.toMediaTypeOrNull()))
            } else {
                this.addFormDataPart(key.toString(), value.toString())
            }
        }
        return this
    }
}