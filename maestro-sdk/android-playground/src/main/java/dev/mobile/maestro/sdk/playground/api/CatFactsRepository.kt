package dev.mobile.maestro.sdk.playground.api

import dev.mobile.maestro.sdk.MaestroSdk
import dev.mobile.maestro.sdk.playground.api.model.CatBreed
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class CatFactsRepository {

    private val api by lazy {
        Retrofit.Builder()
            .baseUrl(MaestroSdk.mockServer().url("https://catfact.ninja/"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(CatFactsApi::class.java)
    }

    fun getBreeds(): List<CatBreed> {
        return api.getBreeds().execute().body()?.data
            ?: error("No response body received")
    }

}