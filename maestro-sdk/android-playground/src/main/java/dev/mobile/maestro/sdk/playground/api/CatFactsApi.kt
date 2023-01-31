package dev.mobile.maestro.sdk.playground.api

import dev.mobile.maestro.sdk.playground.api.model.CatBreedsResponse
import retrofit2.Call
import retrofit2.http.GET

interface CatFactsApi {

    @GET("breeds")
    fun getBreeds(): Call<CatBreedsResponse>

}