package maestro.cli.db

import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class KeyValueStore(private val dbFile: File) {
    private val lock = ReentrantReadWriteLock()

    init {
        dbFile.createNewFile()
    }

    fun get(key: String): String? = lock.read { getCurrentDB()[key] }

    fun set(key: String, value: String) = lock.write {
        val db = getCurrentDB()
        db[key] = value
        commit(db)
    }

    fun delete(key: String) = lock.write {
        val db = getCurrentDB()
        db.remove(key)
        commit(db)
    }

    fun keys(): List<String> = lock.read { getCurrentDB().keys.toList() }

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
