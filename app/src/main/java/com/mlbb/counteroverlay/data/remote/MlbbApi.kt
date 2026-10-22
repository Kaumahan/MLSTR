package com.mlbb.counteroverlay.data.remote

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Live MLBB data (published by Moonton) through the open Rone Arena API:
 * https://arena.rone.dev (source: github.com/ridwaanhall/api-mobilelegends).
 * Docs: https://arena.rone.dev/api/docs
 *
 * Bodies are returned raw and parsed as a JSON tree on a background thread,
 * because the upstream shape contains mixed-type arrays that strict decoding rejects.
 */
interface MlbbApi {

    /** Full roster with roles, lanes and official weak/strong/synergy relations. */
    @GET("heroes/positions")
    suspend fun heroPositions(
        @Query("size") size: Int = 200,
        @Query("index") index: Int = 1,
        @Query("lang") lang: String = "en",
    ): Response<ResponseBody>

    /** Hero list with portrait ("head") URLs. */
    @GET("heroes")
    suspend fun heroes(
        @Query("size") size: Int = 200,
        @Query("index") index: Int = 1,
        @Query("lang") lang: String = "en",
    ): Response<ResponseBody>

    /** Win / ban / pick rates for every hero. */
    @GET("heroes/rank")
    suspend fun heroRank(
        @Query("days") days: Int = 7,
        @Query("rank") rank: String = "all",
        @Query("size") size: Int = 200,
        @Query("lang") lang: String = "en",
    ): Response<ResponseBody>

    /** Hero detail: used for official skill tags and specialities. */
    @GET("heroes/{id}")
    suspend fun heroDetail(
        @Path("id") heroId: Int,
        @Query("lang") lang: String = "en",
    ): Response<ResponseBody>

    /** Item names and icons. */
    @GET("academy/equipment")
    suspend fun equipment(
        @Query("size") size: Int = 500,
        @Query("lang") lang: String = "en",
    ): Response<ResponseBody>

    /** Every hero's win-rate edge against [heroId]: the full matchup table. */
    @GET("academy/heroes/{id}/counters")
    suspend fun matchups(
        @Path("id") heroId: Int,
        @Query("rank") rank: String = "all",
        @Query("lang") lang: String = "en",
    ): Response<ResponseBody>

    /** Most played item builds, emblem and battle spell for [heroId] in [lane]. */
    @GET("academy/heroes/{id}/builds")
    suspend fun builds(
        @Path("id") heroId: Int,
        @Query("lane") lane: String,
        @Query("rank") rank: String = "all",
        @Query("lang") lang: String = "en",
    ): Response<ResponseBody>
}
