package tf.monochrome.android.data.repository

import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tf.monochrome.android.data.auth.SupabaseAuthManager
import tf.monochrome.android.data.auth.UserProfile
import tf.monochrome.android.domain.model.ThemePack
import tf.monochrome.android.domain.model.ThemePackCodec
import tf.monochrome.android.domain.model.ThemePart
import javax.inject.Inject
import javax.inject.Singleton

/** A pack as the store lists it: everything but the pack itself. */
data class StorePack(
    val id: String,
    val authorId: String,
    val authorName: String,
    val name: String,
    val description: String,
    val parts: Set<ThemePart>,
    val accent: Int,
    val background: Int,
    val listed: Boolean,
    val shareCode: String,
    val likes: Int,
    val downloads: Int,
    val createdAt: String,
)

/** How the store lists packs. */
enum class StoreSort { POPULAR, NEW, MINE }

/** Why a store call failed, in the terms the screen speaks. */
sealed class StoreError(message: String) : Exception(message) {
    /** No account, or the session ran out. */
    class SignedOut : StoreError("signed out")

    /** The store's tables are not on the server yet (docs/supabase/theme_store.sql). */
    class NotSetUp : StoreError("store not set up")

    /** Fifty packs already. */
    class LimitReached : StoreError("limit reached")

    /** The pack is not a theme pack, or it is too big. */
    class BadPack : StoreError("bad pack")

    class Network(cause: Throwable) : StoreError(cause.message ?: "network")
}

@Serializable
private data class SbThemePack(
    val id: String = "",
    val user_id: String = "",
    val author_name: String = "",
    val name: String = "",
    val description: String = "",
    val parts: List<String> = emptyList(),
    val accent: Int = 0,
    val background: Int = 0,
    val pack: String? = null,
    val listed: Boolean = true,
    val share_code: String = "",
    val likes: Int = 0,
    val downloads: Int = 0,
    val created_at: String = "",
)

@Serializable
private data class SbThemePackWrite(
    val author_name: String,
    val name: String,
    val description: String,
    val parts: List<String>,
    val accent: Int,
    val background: Int,
    val pack: String,
    val listed: Boolean,
)

@Serializable
private data class SbThemeLike(val pack_id: String)

@Serializable
private data class SbThemeReport(val pack_id: String, val reason: String)

/**
 * The Theme Store on Supabase. Every call needs an account — the tables grant
 * nothing to anonymous users, and the client is built with
 * `requireValidSession`, so a signed-out call fails here before it is sent.
 */
@Singleton
class ThemeStoreRepository @Inject constructor(
    private val auth: SupabaseAuthManager,
    private val packs: ThemePackRepository,
) {
    private val postgrest get() = auth.supabase.postgrest

    val user: StateFlow<UserProfile?> = auth.userProfile

    suspend fun browse(sort: StoreSort, query: String, page: Int): Result<List<StorePack>> = call {
        val uid = requireUser()
        val q = query.trim().replace("%", "").replace("_", "").take(40)
        postgrest[TABLE].select(Columns.list(LIST_COLUMNS)) {
            filter {
                if (sort == StoreSort.MINE) eq("user_id", uid) else eq("listed", true)
                if (q.isNotEmpty()) ilike("name", "%$q%")
            }
            when (sort) {
                StoreSort.POPULAR -> {
                    order("likes", Order.DESCENDING)
                    order("downloads", Order.DESCENDING)
                }
                StoreSort.NEW, StoreSort.MINE -> order("created_at", Order.DESCENDING)
            }
            range((page * PAGE).toLong(), (page * PAGE + PAGE - 1).toLong())
        }.decodeList<SbThemePack>().map { it.toStore() }
    }

    /** The pack itself, decoded and cleaned for this build. */
    suspend fun fetch(id: String): Result<Pair<StorePack, ThemePack>> = call {
        requireUser()
        val row = postgrest[TABLE].select { filter { eq("id", id) } }.decodeSingle<SbThemePack>()
        val pack = packs.decode(row.pack) ?: throw StoreError.BadPack()
        row.toStore() to pack
    }

    /** A pack by its share code, listed or not. Null when no pack has that code. */
    suspend fun byCode(code: String): Result<StorePack?> = call {
        requireUser()
        val clean = code.filter { it.isLetterOrDigit() }.uppercase().take(16)
        if (clean.isEmpty()) return@call null
        postgrest.rpc("theme_pack_by_code", buildJsonObject { put("p_code", clean) })
            .decodeList<SbThemePack>()
            .firstOrNull()
            ?.toStore()
    }

    /** Publishes [pack] (its personal settings already stripped by [ThemePackCodec.forSharing]). */
    suspend fun publish(
        pack: ThemePack,
        name: String,
        description: String,
        listed: Boolean,
    ): Result<StorePack> = call {
        val profile = user.value ?: throw StoreError.SignedOut()
        val clean = ThemePackCodec.forSharing(pack.copy(name = name.trim().take(ThemePack.MAX_NAME)))
        val text = ThemePackCodec.encode(clean)
        if (text.toByteArray(Charsets.UTF_8).size > ThemePackCodec.MAX_BYTES || clean.parts.isEmpty()) {
            throw StoreError.BadPack()
        }
        val colours = clean.colours
        postgrest[TABLE].insert(
            SbThemePackWrite(
                author_name = (profile.displayName ?: "").trim().take(40),
                name = clean.name,
                description = description.trim().take(MAX_DESCRIPTION),
                parts = clean.parts.map { it.name },
                accent = colours?.accent ?: 0,
                background = colours?.background ?: 0,
                pack = text,
                listed = listed,
            ),
        ) { select(Columns.list(LIST_COLUMNS)) }.decodeSingle<SbThemePack>().toStore()
    }

    suspend fun setListed(id: String, listed: Boolean): Result<Unit> = call {
        requireUser()
        postgrest[TABLE].update({ set("listed", listed) }) { filter { eq("id", id) } }
        Unit
    }

    suspend fun delete(id: String): Result<Unit> = call {
        requireUser()
        postgrest[TABLE].delete { filter { eq("id", id) } }
        Unit
    }

    /** The ids among [ids] this account likes. */
    suspend fun likedAmong(ids: List<String>): Result<Set<String>> = call {
        val uid = requireUser()
        if (ids.isEmpty()) return@call emptySet()
        postgrest[LIKES].select(Columns.list("pack_id")) {
            filter {
                eq("user_id", uid)
                isIn("pack_id", ids)
            }
        }.decodeList<SbThemeLike>().map { it.pack_id }.toSet()
    }

    suspend fun setLiked(id: String, liked: Boolean): Result<Unit> = call {
        val uid = requireUser()
        if (liked) {
            postgrest[LIKES].upsert(SbThemeLike(id)) { ignoreDuplicates = true }
        } else {
            postgrest[LIKES].delete { filter { eq("pack_id", id); eq("user_id", uid) } }
        }
        Unit
    }

    /** Counts a download, once per person; failures are not worth telling anyone about. */
    suspend fun markDownloaded(id: String) {
        runCatching { postgrest.rpc("theme_pack_downloaded", buildJsonObject { put("p_id", id) }) }
    }

    suspend fun report(id: String, reason: String): Result<Unit> = call {
        requireUser()
        try {
            postgrest[REPORTS].insert(SbThemeReport(id, reason.trim().take(MAX_DESCRIPTION)))
        } catch (e: PostgrestRestException) {
            // Reported already: one report per person and pack is the point.
            if (e.code != "23505") throw e
        }
        Unit
    }

    private fun requireUser(): String = user.value?.id ?: throw StoreError.SignedOut()

    private suspend fun <T> call(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: StoreError) {
            Result.failure(e)
        } catch (e: PostgrestRestException) {
            Result.failure(
                when {
                    // PostgREST's "relation does not exist" / "function not found".
                    e.code == "42P01" || e.code == "PGRST202" || e.code == "PGRST205" -> StoreError.NotSetUp()
                    e.message?.contains("theme pack limit reached") == true -> StoreError.LimitReached()
                    e.statusCode == 401 -> StoreError.SignedOut()
                    else -> StoreError.Network(e)
                },
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(
                if (e.javaClass.simpleName == "SessionRequiredException") StoreError.SignedOut() else StoreError.Network(e),
            )
        }

    private fun SbThemePack.toStore() = StorePack(
        id = id,
        authorId = user_id,
        authorName = author_name,
        name = name,
        description = description,
        parts = parts.mapNotNull { p -> ThemePart.entries.firstOrNull { it.name == p } }.toSet(),
        accent = accent,
        background = background,
        listed = listed,
        shareCode = share_code,
        likes = likes,
        downloads = downloads,
        createdAt = created_at,
    )

    private companion object {
        const val TABLE = "theme_packs"
        const val LIKES = "theme_likes"
        const val REPORTS = "theme_reports"
        const val PAGE = 20
        const val MAX_DESCRIPTION = 280
        val LIST_COLUMNS = listOf(
            "id", "user_id", "author_name", "name", "description", "parts", "accent", "background",
            "listed", "share_code", "likes", "downloads", "created_at",
        )
    }
}
