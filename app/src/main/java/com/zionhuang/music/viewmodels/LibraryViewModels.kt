@file:OptIn(ExperimentalCoroutinesApi::class)

package com.zionhuang.music.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.offline.Download.STATE_COMPLETED
import com.zionhuang.innertube.YouTube
import com.zionhuang.music.constants.*
import com.zionhuang.music.db.MusicDatabase
import com.zionhuang.music.extensions.reversed
import com.zionhuang.music.extensions.toEnum
import com.zionhuang.music.playback.DownloadUtil
import com.zionhuang.music.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import javax.inject.Inject

@HiltViewModel
class LibrarySongsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    downloadUtil: DownloadUtil,
) : ViewModel() {
    val allSongs = context.dataStore.data
        .map {
            Triple(
                it[SongViewTypeKey].toEnum(SongViewType.LIBRARY),
                it[SongSortTypeKey].toEnum(SongSortType.CREATE_DATE),
                (it[SongSortDescendingKey] ?: true)
            )
        }
        .distinctUntilChanged()
        .flatMapLatest { (viewType, sortType, descending) ->
            when (viewType) {
                SongViewType.LIBRARY -> database.songs(sortType, descending)
                SongViewType.LIKED -> database.likedSongs(sortType, descending)
                SongViewType.DOWNLOADED -> downloadUtil.downloads.flatMapLatest { downloads ->
                    database.songs(
                        downloads.filter { (_, download) ->
                            download.state == STATE_COMPLETED
                        }.keys.toList()
                    ).map { songs ->
                        when (sortType) {
                            SongSortType.CREATE_DATE -> songs.sortedBy { downloads[it.id]?.updateTimeMs ?: 0L }
                            SongSortType.NAME -> songs.sortedBy { it.song.title }
                            SongSortType.ARTIST -> songs.sortedBy { song ->
                                song.artists.joinToString(separator = "") { it.name }
                            }

                            SongSortType.PLAY_TIME -> songs.sortedBy { it.song.totalPlayTime }
                        }.reversed(descending)
                    }
                }
            }

        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
}

@HiltViewModel
class LibraryArtistsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
) : ViewModel() {
    val allArtists = context.dataStore.data
        .map {
            Triple(
                it[ArtistViewTypeKey].toEnum(ArtistViewType.LIBRARY),
                it[ArtistSortTypeKey].toEnum(ArtistSortType.CREATE_DATE),
                it[ArtistSortDescendingKey] ?: true
            )
        }
        .distinctUntilChanged()
        .flatMapLatest { (viewType, sortType, descending) ->
            when (viewType) {
                ArtistViewType.LIBRARY -> database.artists(sortType, descending)
                ArtistViewType.BOOKMARKED -> database.artistsBookmarked(sortType, descending)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch {
            allArtists.collect { artists ->
                artists
                    .map { it.artist }
                    .filter {
                        it.thumbnailUrl == null || Duration.between(it.lastUpdateTime, LocalDateTime.now()) > Duration.ofDays(10)
                    }
                    .forEach { artist ->
                        YouTube.artist(artist.id).onSuccess { artistPage ->
                            database.query {
                                update(artist, artistPage)
                            }
                        }
                    }
            }
        }
    }
}

@HiltViewModel
class LibraryAlbumsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
) : ViewModel() {
    val allAlbums = context.dataStore.data
        .map {
            it[AlbumSortTypeKey].toEnum(AlbumSortType.CREATE_DATE) to (it[AlbumSortDescendingKey] ?: true)
        }
        .distinctUntilChanged()
        .flatMapLatest { (sortType, descending) ->
            database.albums(sortType, descending)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
}

@HiltViewModel
class LibraryPlaylistsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
) : ViewModel() {
    val allPlaylists = context.dataStore.data
        .map {
            it[PlaylistSortTypeKey].toEnum(PlaylistSortType.CREATE_DATE) to (it[PlaylistSortDescendingKey] ?: true)
        }
        .distinctUntilChanged()
        .flatMapLatest { (sortType, descending) ->
            database.playlists(sortType, descending)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
}

@HiltViewModel
class ArtistSongsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val artistId = savedStateHandle.get<String>("artistId")!!
    val artist = database.artist(artistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val songs = context.dataStore.data
        .map {
            it[ArtistSongSortTypeKey].toEnum(ArtistSongSortType.CREATE_DATE) to (it[ArtistSongSortDescendingKey] ?: true)
        }
        .distinctUntilChanged()
        .flatMapLatest { (sortType, descending) ->
            database.artistSongs(artistId, sortType, descending)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
}
