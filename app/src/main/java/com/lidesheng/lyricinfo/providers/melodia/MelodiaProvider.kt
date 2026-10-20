package com.lidesheng.lyricinfo.providers.melodia

import android.annotation.SuppressLint
import android.media.MediaMetadata
import android.os.Bundle
import android.util.Log
import com.lidesheng.lyricinfo.core.BaseLyricProvider
import com.lidesheng.lyricinfo.core.LyricResult
import com.lidesheng.lyricinfo.providers.netease.NeteaseApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Melodia provider.
 *
 * Melodia (https://github.com/rinchao0721/Melodia) is a third-party Netease
 * Cloud Music client built on Media3. Its MediaItem ID is the Netease song ID,
 * which Media3 publishes as the framework METADATA_KEY_MEDIA_ID. Local and
 * external-source tracks use non-positive IDs and are skipped.
 *
 * TITLE may be replaced by the current lyric line when Melodia's Bluetooth
 * lyric option is enabled, so display metadata comes from the Netease song
 * detail API instead. Melodia's own lyricInfo extra does not follow the
 * LyricInfo field contract and is never used as a source; it is overwritten
 * once the API lyric is available.
 *
 * Media3 only republishes metadata when the MediaItem metadata changes, so the
 * provider retains the latest framework MediaSession and re-submits it after
 * the asynchronous lyric request completes.
 */
@SuppressLint("SoonBlockedPrivateApi")
class MelodiaProvider : BaseLyricProvider() {

    companion object {
        private const val TAG = "LyricInfo"
        const val PACKAGE_NAME = "com.lin0721.linmusic"
    }

    override val packageName = PACKAGE_NAME

    private data class FrameworkTarget(
        val session: Any,
        val metadata: MediaMetadata,
        val mediaId: String
    )

    private val metadataCache = ConcurrentHashMap<String, NeteaseApi.SongMetadata>()
    private val metadataFetchingIds = ConcurrentHashMap.newKeySet<String>()
    private val missingMediaIdLogged = AtomicBoolean(false)
    private val metadataExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LyricInfo-MelodiaMetadata").apply { isDaemon = true }
    }

    @Volatile
    private var frameworkTarget: FrameworkTarget? = null

    override fun onDestroy() {
        metadataExecutor.shutdownNow()
        metadataCache.clear()
        metadataFetchingIds.clear()
        frameworkTarget = null
        super.onDestroy()
    }

    override fun resolveTrackMetadata(bundle: Bundle): TrackMetadata? {
        val songId = readMediaId(bundle)
        if (songId.isBlank()) {
            if (missingMediaIdLogged.compareAndSet(false, true)) {
                Log.w(TAG, "[Melodia] No Netease song ID; skip LyricInfo")
            }
            return null
        }

        metadataCache[songId]?.let { return it.toTrack() }
        loadCachedLyric(songId)?.let { cached ->
            return TrackMetadata(
                songName = cached.songName,
                artist = cached.artist,
                album = cached.album,
                songId = songId
            )
        }
        requestSongMetadata(songId)
        return null
    }

    override fun onMediaSessionMetadataObserved(session: Any, metadata: MediaMetadata) {
        val mediaId = runCatching { readMediaId(metadataBundle(metadata)) }.getOrDefault("")
        frameworkTarget = mediaId.takeIf { it.isNotBlank() }?.let {
            FrameworkTarget(session, metadata, it)
        }
    }

    override fun onLyricAvailable(track: TrackMetadata, result: LyricResult) {
        refreshFrameworkSession(track, result)
    }

    override fun fetchLyric(mediaId: String, title: String?, artist: String?): LyricResult? {
        val id = mediaId.toLongOrNull()
        if (id == null || id <= 0L) {
            Log.w(TAG, "[Melodia] Invalid ID: $mediaId")
            return null
        }
        return NeteaseApi.fetchLyric(id)
    }

    private fun requestSongMetadata(songId: String) {
        if (metadataCache.containsKey(songId) || !metadataFetchingIds.add(songId)) return

        Log.i(TAG, "[Melodia] Query song metadata: id=$songId")
        metadataExecutor.execute {
            try {
                val metadata = NeteaseApi.fetchSongMetadata(songId.toLong())
                if (metadata == null) {
                    Log.w(TAG, "[Melodia] No song metadata: id=$songId")
                    return@execute
                }

                metadataCache[songId] = metadata
                val track = metadata.toTrack()
                Log.i(TAG, "[Melodia] [Song] ${track.songName} - ${track.artist}")
                requestLyric(track, " (metadata)")
            } catch (e: Exception) {
                Log.e(TAG, "[Melodia] ✗ Query song metadata: id=$songId", e)
            } finally {
                metadataFetchingIds.remove(songId)
            }
        }
    }

    /**
     * The first metadata event of a song only starts the network requests.
     * Re-submit the retained metadata once the lyric is ready, but only while
     * the session still shows the same song.
     */
    private fun refreshFrameworkSession(track: TrackMetadata, result: LyricResult) {
        val target = frameworkTarget ?: return
        if (target.mediaId != track.cacheKey) return
        try {
            val bundle = metadataBundle(target.metadata)
            if (readMediaId(bundle) != target.mediaId) return
            if (!putLyricInfo(bundle, track, result, " (asyncSession)")) return
            if (frameworkTarget !== target) return

            target.session.javaClass
                .getDeclaredMethod("setMetadata", MediaMetadata::class.java)
                .apply { isAccessible = true }
                .invoke(target.session, target.metadata)
            Log.i(TAG, "[Melodia] ✓ Refreshed MediaSession: ${target.mediaId}")
        } catch (e: Exception) {
            Log.e(TAG, "[Melodia] ✗ Refresh MediaSession", e)
        }
    }

    private fun NeteaseApi.SongMetadata.toTrack() = TrackMetadata(
        songName = songName,
        artist = artist,
        album = album,
        songId = songId
    )

    private fun metadataBundle(metadata: MediaMetadata): Bundle {
        val bundleField = metadata.javaClass.getDeclaredField("mBundle").apply {
            isAccessible = true
        }
        return bundleField.get(metadata) as Bundle
    }

    private fun readMediaId(bundle: Bundle): String {
        val songId = bundle.getCharSequence(MediaMetadata.METADATA_KEY_MEDIA_ID)
            ?.toString()
            ?.trim()
            .orEmpty()
        return songId.takeIf { it.toLongOrNull()?.let { value -> value > 0L } == true }.orEmpty()
    }
}
