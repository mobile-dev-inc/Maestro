package maestro

import okio.Source

class NamedSource(val name: String, val source: Source, val extension: String, val path: String)

enum class MediaExt(val extName: String) {
    PNG("png"),
    JPEG("jpeg"),
    JPG("jpg"),
    GIF("gif"),
    MP4("mp4"),
}