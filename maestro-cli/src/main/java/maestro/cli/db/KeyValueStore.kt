package maestro.cli.db

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.Channels
import java.nio.channels.FileLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class KeyValueStore(private val dbFile: File) {
    private val lock = ReentrantReadWriteLock()

    init {
        dbFile.createNewFile()
    }

    fun get(key: String): String? = lock.read { withFileLock { getCurrentDB(it)[key] } }

    fun set(key: String, value: String) = lock.write {
        withFileLock { raf ->
            val db = getCurrentDB(raf)
            db[key] = value
            commit(raf, db)
        }
    }

    fun delete(key: String) = lock.write {
        withFileLock { raf ->
            val db = getCurrentDB(raf)
            db.remove(key)
            commit(raf, db)
        }
    }

    fun keys(): List<String> = lock.read { withFileLock { getCurrentDB(it).keys.toList() } }

    private fun getCurrentDB(raf: RandomAccessFile): MutableMap<String, String> {
        if (raf.length() == 0L) return mutableMapOf()
        raf.seek(0)
        // The channel stays open until withFileLock releases the lock and closes the file.
        return Channels.newInputStream(raf.channel)
            .bufferedReader(Charsets.UTF_8)
            .lineSequence()
            .filter { it.contains("=") }
            .associate { line ->
                val (key, value) = line.split("=", limit = 2)
                key to value
            }
            .toMutableMap()
    }

    private fun commit(raf: RandomAccessFile, db: MutableMap<String, String>) {
        val bytes = db.map { (key, value) -> "$key=$value" }
            .joinToString("\n")
            .toByteArray(Charsets.UTF_8)
        raf.seek(0)
        raf.write(bytes)
        raf.setLength(bytes.size.toLong())
    }

    private fun <T> withFileLock(block: (RandomAccessFile) -> T): T {
        val raf = RandomAccessFile(dbFile, "rw")
        return try {
            val channel = raf.channel
            val fileLock: FileLock = channel.lock()
            try {
                block(raf)
            } finally {
                fileLock.release()
            }
        } finally {
            raf.close()
        }
    }
}
