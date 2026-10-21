// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import tf.monochrome.android.data.db.entity.CachedLyricsEntity

/**
 * Lyrics a server timed for a song, kept for good: a song costs the server
 * minutes once, and plays with its words from then on, offline too.
 */
@Dao
interface LyricsCacheDao {
    @Query("SELECT * FROM cached_lyrics WHERE trackId = :trackId")
    suspend fun get(trackId: Long): CachedLyricsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: CachedLyricsEntity)

    @Query("DELETE FROM cached_lyrics WHERE trackId = :trackId")
    suspend fun delete(trackId: Long)
}
