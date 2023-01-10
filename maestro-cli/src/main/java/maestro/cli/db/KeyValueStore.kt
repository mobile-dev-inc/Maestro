package maestro.cli.db

import java.io.File
import java.io.RandomAccessFile

class KeyValueStore(
    private val dbFile: File
) {

    private val db by lazy {
        dbFile
            .readLines()
            .associate { line ->
                val (key, value) = line.split("=", limit = 2)
                key to value
            }
            .toMutableMap()
    }

    fun get(key: String): String? {
        return db[key]
    }

    fun set(key: String, value: String) {
        db[key] = value
        commit()
    }

    fun delete(key: String) {
        db.remove(key)
        commit()
    }

    fun keys(): List<String> {
        return db.keys.toList()
    }

    fun <T> withExclusiveLock(block: () -> T): T {
        val channel = RandomAccessFile(dbFile, "rw").channel

        val lock = channel.lock()
        return try {
            block()
        } finally {
            lock.release()
            channel.close()
        }
    }

    private fun commit() {
        dbFile.writeText(
            db
                .map { (key, value) -> "$key=$value" }
                .joinToString("\n")
        )
    }

}