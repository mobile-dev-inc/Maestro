package maestro.cli.db

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class KeyValueStore(private val dbFile: File) {
    private val lock = ReentrantReadWriteLock()

    init {
        dbFile.createNewFile()
    }

    fun get(key: String): String? = lock.read { withFileLock { getCurrentDB()[key] } }

    fun set(key: String, value: String) = lock.write {
        withFileLock {
            val db = getCurrentDB()
            db[key] = value
            commit(db)
        }
    }

    fun delete(key: String) = lock.write {
        withFileLock {
            val db = getCurrentDB()
            db.remove(key)
            commit(db)
        }
    }

    fun keys(): List<String> = lock.read { withFileLock { getCurrentDB().keys.toList() } }

    private fun getCurrentDB(): MutableMap<String, String> {
        if (dbFile.length() == 0L) return mutableMapOf()
        return dbFile
            .readLines()
            .filter { it.contains("=") }
            .associate { line ->
                val (key, value) = line.split("=", limit = 2)
                key to value
            }
            .toMutableMap()
    }

    private fun commit(db: MutableMap<String, String>) {
        dbFile.writeText(
            db.map { (key, value) -> "$key=$value" }
                .joinToString("\n")
        )
    }

    private fun <T> withFileLock(block: () -> T): T {
        val raf = RandomAccessFile(dbFile, "rw")
        return try {
            val channel = raf.channel
            val fileLock: FileLock = channel.lock()
            try {
                block()
            } finally {
                fileLock.release()
            }
        } finally {
            raf.close()
        }
    }
}
