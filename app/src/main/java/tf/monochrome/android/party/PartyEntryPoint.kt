// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.party

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** The party, for the app's root, which is no Hilt view model: the song menus' "Add to party", and invites. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PartyEntryPoint {
    fun partyController(): PartyController
    fun partyInvites(): PartyInvites
}
