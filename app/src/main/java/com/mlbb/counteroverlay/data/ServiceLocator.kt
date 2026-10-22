package com.mlbb.counteroverlay.data

import android.content.Context
import coil.ImageLoader
import com.mlbb.counteroverlay.data.local.JsonFileCache
import com.mlbb.counteroverlay.data.remote.NetworkModule
import com.mlbb.counteroverlay.data.repo.HeroRepository
import com.mlbb.counteroverlay.detection.DraftScreenDetector
import com.mlbb.counteroverlay.detection.HeroIconMatcher
import com.mlbb.counteroverlay.detection.ScreenCaptureManager

/** Tiny manual DI; swap for Hilt/Koin if the app grows. */
object ServiceLocator {
    @Volatile private var repo: HeroRepository? = null
    @Volatile private var captureManager: ScreenCaptureManager? = null
    @Volatile private var iconMatcher: HeroIconMatcher? = null
    @Volatile private var detector: DraftScreenDetector? = null

    fun heroRepository(context: Context): HeroRepository =
        repo ?: synchronized(this) {
            repo ?: HeroRepository(
                context = context.applicationContext,
                api = NetworkModule.api,
                cache = JsonFileCache(context.applicationContext),
                json = NetworkModule.json,
            ).also { repo = it }
        }

    fun screenCaptureManager(context: Context): ScreenCaptureManager =
        captureManager ?: synchronized(this) {
            captureManager ?: ScreenCaptureManager(context.applicationContext).also { captureManager = it }
        }

    fun heroIconMatcher(context: Context): HeroIconMatcher =
        iconMatcher ?: synchronized(this) {
            iconMatcher ?: HeroIconMatcher(
                context = context.applicationContext,
                imageLoader = ImageLoader(context.applicationContext),
            ).also { iconMatcher = it }
        }

    fun draftScreenDetector(context: Context): DraftScreenDetector =
        detector ?: synchronized(this) {
            detector ?: DraftScreenDetector(
                context = context.applicationContext,
                screenCapture = screenCaptureManager(context),
                iconMatcher = heroIconMatcher(context),
            ).also { detector = it }
        }
}
