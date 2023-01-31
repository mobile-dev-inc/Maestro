package dev.mobile.maestro.sdk.playground

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.mobile.maestro.sdk.playground.api.CatFactsRepository
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val repository = CatFactsRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            try {
                setResult("Loading...")

                val breeds = repository.getBreeds()

                setResult(breeds.joinToString("\n"))
            } catch (e: Exception) {
                Log.e("SDK Playground", "Failed to fetch cat breeds", e)

                setResult(e.stackTraceToString())
            }
        }
    }

    private fun setResult(text: String) {
        runOnUiThread {
            findViewById<TextView>(R.id.result).text = text
        }
    }

}