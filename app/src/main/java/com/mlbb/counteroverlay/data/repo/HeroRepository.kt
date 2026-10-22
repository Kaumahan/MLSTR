package com.mlbb.counteroverlay.data.repo

import android.content.Context
import android.util.Log
import com.mlbb.counteroverlay.data.local.JsonFileCache
import com.mlbb.counteroverlay.data.model.BuildUi
import com.mlbb.counteroverlay.data.model.CounterStat
import com.mlbb.counteroverlay.data.model.DataSource
import com.mlbb.counteroverlay.data.model.Lane
import com.mlbb.counteroverlay.data.model.Loadable
import com.mlbb.counteroverlay.data.model.Rank
import com.mlbb.counteroverlay.data.model.RateUi
import com.mlbb.counteroverlay.data.model.Roster
import com.mlbb.counteroverlay.data.remote.ApiFormatException
import com.mlbb.counteroverlay.data.remote.ApiParsers
import com.mlbb.counteroverlay.data.remote.MlbbApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Outcome of one network attempt. Never throws (except cancellation). */
sealed interface RemoteResult<out T> {
    data class Success<T>(val value: T) : RemoteResult<T>
    data class HttpError(val code: Int) : RemoteResult<Nothing>
    data class NetworkError(val cause: IOException) : RemoteResult<Nothing>
    data class ParseError(val cause: Exception) : RemoteResult<Nothing>
}

fun RemoteResult<*>.message(): String? = when (this) {
    is RemoteResult.Success -> null
    is RemoteResult.HttpError -> when (code) {
        404 -> "No data for this hero yet"
        429, 503 -> "Server is busy, showing saved data"
        else -> "Server error ($code)"
    }
    is RemoteResult.NetworkError -> "You're offline, showing saved data"
    is RemoteResult.ParseError -> "Couldn't read the server's data"
}

/**
 * Stale-while-revalidate for every resource:
 *   memory -> disk cache -> (bundled seed for the roster) shown instantly,
 *   then refreshed from the network when older than its max age.
 * The disk cache stores compact models, never the large raw responses.
 */
class HeroRepository(
    private val context: Context,
    private val api: MlbbApi,
    private val cache: JsonFileCache,
    private val json: Json,
) {
    private val memory = ConcurrentHashMap<String, Loadable<*>>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    // ------------------------------------------------------------------ public API

    fun roster(forceRefresh: Boolean = false): Flow<Loadable<Roster>> =
        cached("roster", TimeUnit.HOURS.toMillis(12), Roster.serializer(), forceRefresh,
            seed = { loadSeed() }) { fetchRoster() }
            .map { l -> l.copy(value = l.value?.let { withSeedTraits(it) }) }

    /** Full matchup table against [heroId] at [rank], best counters first. */
    fun matchups(heroId: Int, rank: Rank = Rank.ALL, forceRefresh: Boolean = false): Flow<Loadable<List<CounterStat>>> =
        cached("matchups_v2_${heroId}_${rank.apiKey}"  /* v2: corrected direction; ignores old caches */, TimeUnit.HOURS.toMillis(6), ListSerializer(CounterStat.serializer()), forceRefresh) {
            val raw = api.matchups(heroId, rank.apiKey).bodyOrThrow()
            withContext(Dispatchers.Default) { ApiParsers.parseMatchups(json, raw) }
        }

    /** Popular builds for [heroId] in [lane] at [rank]. */
    fun builds(heroId: Int, lane: Lane, rank: Rank = Rank.ALL, forceRefresh: Boolean = false): Flow<Loadable<List<BuildUi>>> =
        cached("builds_${heroId}_${lane.apiKey}_${rank.apiKey}", TimeUnit.HOURS.toMillis(12), ListSerializer(BuildUi.serializer()), forceRefresh) {
            val raw = api.builds(heroId, lane.apiKey, rank.apiKey).bodyOrThrow()
            withContext(Dispatchers.Default) { ApiParsers.parseBuilds(json, raw) }
        }

    /** Win/ban/pick rates for every hero at [rank] (last 7 days). */
    fun rates(rank: Rank, forceRefresh: Boolean = false): Flow<Loadable<Map<Int, RateUi>>> =
        cached("rates_${rank.apiKey}", TimeUnit.HOURS.toMillis(12), MapSerializer(Int.serializer(), RateUi.serializer()), forceRefresh) {
            val raw = api.heroRank(rank = rank.apiKey).bodyOrThrow()
            withContext(Dispatchers.Default) {
                ApiParsers.parseRates(json, raw).mapValues { (_, r) -> RateUi(r.winRate, r.banRate, r.pickRate) }
            }
        }

    /**
     * Official skill tags for a hero that isn't in the bundled snapshot yet
     * (e.g. a hero released after this app version). Cached for a week.
     */
    fun traits(heroId: Int): Flow<Loadable<List<String>>> =
        cached("traits_$heroId", TimeUnit.DAYS.toMillis(7), ListSerializer(String.serializer()), false) {
            val raw = api.heroDetail(heroId).bodyOrThrow()
            withContext(Dispatchers.Default) { ApiParsers.parseHeroTraits(json, raw) }
        }

    // ------------------------------------------------------------------ roster fetch

    /** Four calls in parallel; only the hero list is mandatory, the rest enrich it. */
    private suspend fun fetchRoster(): Roster = coroutineScope {
        val positions = async { api.heroPositions().bodyOrThrow() }
        val icons = async { runCatching { api.heroes().bodyOrThrow() }.getOrNull() }
        val rank = async { runCatching { api.heroRank().bodyOrThrow() }.getOrNull() }
        val items = async { runCatching { api.equipment().bodyOrThrow() }.getOrNull() }

        val heroesRaw = positions.await()
        val iconsRaw = icons.await(); val rankRaw = rank.await(); val itemsRaw = items.await()

        withContext(Dispatchers.Default) {
            val heroes = ApiParsers.parseHeroPage(json, heroesRaw).heroes
            if (heroes.isEmpty()) throw ApiFormatException("Empty hero list")
            val iconMap = iconsRaw?.let { runCatching { ApiParsers.parseHeroIcons(json, it) }.getOrNull() }.orEmpty()
            val rates = rankRaw?.let { runCatching { ApiParsers.parseRates(json, it) }.getOrNull() }.orEmpty()
            val itemMap = itemsRaw?.let { runCatching { ApiParsers.parseItems(json, it) }.getOrNull() }.orEmpty()

            // Keep previously cached icons/items if one of the optional calls failed.
            val previous = (memory["roster"]?.value as? Roster)
            Roster(
                heroes = heroes.map { h ->
                    val r = rates[h.id]
                    h.copy(
                        icon = iconMap[h.id] ?: previous?.byId?.get(h.id)?.icon,
                        winRate = r?.winRate, banRate = r?.banRate, pickRate = r?.pickRate,
                    )
                }.distinctBy { it.id }.sortedBy { it.name.lowercase() },
                items = itemMap.ifEmpty { previous?.items.orEmpty() },
            )
        }
    }

    @Volatile private var seedCache: Roster? = null

    private suspend fun loadSeed(): Roster? = seedCache ?: withContext(Dispatchers.IO) {
        runCatching {
            val text = context.assets.open("roster_seed.json").bufferedReader().use { it.readText() }
            json.decodeFromString(Roster.serializer(), text)
        }.getOrNull()?.also { seedCache = it }
    }

    /** Skill tags rarely change, so they come from the bundled snapshot instead of 130+ extra calls. */
    private suspend fun withSeedTraits(r: Roster): Roster {
        if (r.heroes.all { it.traits.isNotEmpty() }) return r
        val seed = loadSeed()?.byId ?: return r
        return r.copy(heroes = r.heroes.map { h -> if (h.traits.isEmpty()) h.copy(traits = seed[h.id]?.traits.orEmpty()) else h })
    }

    // ------------------------------------------------------------------ generic SWR

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> cached(
        key: String,
        maxAgeMillis: Long,
        serializer: KSerializer<T>,
        forceRefresh: Boolean,
        seed: (suspend () -> T?)? = null,
        fetch: suspend () -> T,
    ): Flow<Loadable<T>> = flow {
        var current: Loadable<T> = (memory[key] as Loadable<T>?)
            ?: cache.read(key)?.let { e ->
                decode(serializer, e.text)?.let { Loadable(it, DataSource.DISK_CACHE, e.savedAtMillis) }
            }
            ?: seed?.invoke()?.let { Loadable(it, DataSource.BUNDLED, 0L) } // 0 = always stale
            ?: Loadable()
        memory[key] = current

        val stale = current.value == null || System.currentTimeMillis() - current.fetchedAtMillis > maxAgeMillis
        if (!forceRefresh && !stale) { emit(current); return@flow }
        emit(current.copy(isLoading = true, error = null))

        val lock = locks.getOrPut(key) { Mutex() }
        val result = lock.withLock { safeCall(fetch) }
        current = when (result) {
            is RemoteResult.Success -> {
                runCatching { cache.write(key, json.encodeToString(serializer, result.value)) }
                Loadable(result.value, DataSource.NETWORK, System.currentTimeMillis())
            }
            else -> current.copy(error = result.message())
        }.copy(isLoading = false)

        memory[key] = current
        emit(current)
    }.flowOn(Dispatchers.IO)

    private suspend fun <T> decode(serializer: KSerializer<T>, text: String): T? =
        withContext(Dispatchers.Default) { runCatching { json.decodeFromString(serializer, text) }.getOrNull() }

    private suspend fun Response<ResponseBody>.bodyOrThrow(): String {
        if (!isSuccessful) throw HttpStatus(code())
        return withContext(Dispatchers.IO) { body()?.string().orEmpty() }
    }

    private class HttpStatus(val code: Int) : Exception()

    private suspend fun <T> safeCall(block: suspend () -> T): RemoteResult<T> = try {
        RemoteResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpStatus) {
        Log.w(TAG, "HTTP ${e.code}"); RemoteResult.HttpError(e.code)
    } catch (e: IOException) {
        Log.w(TAG, "Network failure", e); RemoteResult.NetworkError(e)
    } catch (e: SerializationException) {
        Log.e(TAG, "Parse failure", e); RemoteResult.ParseError(e)
    } catch (e: IllegalStateException) { // includes ApiFormatException
        Log.e(TAG, "Bad payload", e); RemoteResult.ParseError(e)
    } catch (e: IllegalArgumentException) {
        Log.e(TAG, "Bad payload", e); RemoteResult.ParseError(e)
    }

    private companion object { const val TAG = "HeroRepository" }
}
