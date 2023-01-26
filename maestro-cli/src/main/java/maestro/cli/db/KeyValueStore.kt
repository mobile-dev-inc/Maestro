package maestro.cli.db

import java.io.File
import java.io.RandomAccessFile

class KeyValueStore(
    private val dbFile: File
) {

    fun get(key: String): String? {
        val db = getCurrentDB()
        return db[key]
    }

    fun set(key: String, value: String) {
        val db = getCurrentDB()
        db[key] = value
        commit(db)
    }

    fun delete(key: String) {
        val db = getCurrentDB()
        db.remove(key)
        commit(db)
    }

    fun keys(): List<String> {
        val db = getCurrentDB()
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

    private fun getCurrentDB(): MutableMap<String, String> {
        return dbFile
            .readLines()
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

}