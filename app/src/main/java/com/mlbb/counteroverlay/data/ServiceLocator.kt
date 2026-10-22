package com.mlbb.counteroverlay.data

import android.content.Context
import com.mlbb.counteroverlay.data.local.JsonFileCache
import com.mlbb.counteroverlay.data.remote.NetworkModule
import com.mlbb.counteroverlay.data.repo.HeroRepository

/** Tiny manual DI; swap for Hilt/Koin if the app grows. */
object ServiceLocator {
    @Volatile private var repo: HeroRepository? = null

    fun heroRepository(context: Context): HeroRepository =
        repo ?: synchronized(this) {
            repo ?: HeroRepository(
                context = context.applicationContext,
                api = NetworkModule.api,
                cache = JsonFileCache(context.applicationContext),
                json = NetworkModule.json,
            ).also { repo = it }
        }
}
