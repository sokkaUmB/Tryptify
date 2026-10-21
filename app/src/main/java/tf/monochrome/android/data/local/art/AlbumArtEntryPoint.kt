// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.local.art

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** "Find album art" for the app's root, which hands it to every song menu and selection bar. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AlbumArtEntryPoint {
    fun albumArtFinder(): AlbumArtFinder
}
