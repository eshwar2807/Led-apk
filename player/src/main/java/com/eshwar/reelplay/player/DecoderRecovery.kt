package com.eshwar.reelplay.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.util.concurrent.ConcurrentHashMap

/**
 * Picks decoders the usual way, minus any that already failed on this device. After a phone's
 * hardware decoder chokes on a file, the next attempt goes to its next decoder, then Android's
 * software one, and for audio finally FFmpeg.
 */
@OptIn(UnstableApi::class)
class RecoveringCodecSelector : MediaCodecSelector {
    private val blocked = ConcurrentHashMap.newKeySet<String>()

    override fun getDecoderInfos(mimeType: String, requiresSecureDecoder: Boolean, requiresTunnelingDecoder: Boolean): List<MediaCodecInfo> =
        MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            .filter { it.name !in blocked }

    /** False if it was already blocked (so blocking it again won't change anything). */
    fun block(name: String): Boolean = blocked.add(name)
}

/**
 * Gets playback going again after a decoder error instead of stopping: first by skipping the
 * decoder that failed, then (for sound) by switching to another audio track, and as a last
 * resort by playing without the track that can't be decoded. A few attempts per video.
 */
@OptIn(UnstableApi::class)
class DecoderRecovery(private val player: ExoPlayer, private val selector: RecoveringCodecSelector) {

    private var attempts = 0
    private val triedAudioGroups = HashSet<Int>()

    /** Call when a new video starts. */
    fun reset() {
        attempts = 0
        triedAudioGroups.clear()
    }

    /** Returns a message describing what was done, or null if nothing more can be tried. */
    fun recover(e: PlaybackException): String? {
        if (e !is ExoPlaybackException || e.type != ExoPlaybackException.TYPE_RENDERER) return null
        if (e.errorCode !in DECODER_ERRORS || attempts >= MAX_ATTEMPTS) return null
        attempts++
        val trackType = runCatching { player.getRendererType(e.rendererIndex) }.getOrDefault(C.TRACK_TYPE_UNKNOWN)
        val what = describe(e.rendererFormat, trackType)
        val decoder = when (val cause = e.cause) {
            is MediaCodecDecoderException -> cause.codecInfo?.name
            is MediaCodecRenderer.DecoderInitializationException -> cause.codecInfo?.name
            else -> null
        }
        val message = when {
            decoder != null && selector.block(decoder) -> "The phone's decoder couldn't handle the $what; trying another decoder"
            trackType == C.TRACK_TYPE_AUDIO -> switchAudio(what)
            trackType == C.TRACK_TYPE_TEXT -> {
                disable(C.TRACK_TYPE_TEXT)
                "Subtitles turned off: they couldn't be read"
            }
            else -> null
        } ?: return null
        val position = player.currentPosition
        player.prepare()
        player.seekTo(position)
        player.play()
        return message
    }

    private fun switchAudio(what: String): String {
        val groups = player.currentTracks.groups.withIndex()
            .filter { (_, g) -> g.type == C.TRACK_TYPE_AUDIO && g.isSupported }
        groups.firstOrNull { (_, g) -> g.isSelected }?.let { (i, _) -> triedAudioGroups += i }
        val next = groups.firstOrNull { (i, _) -> i !in triedAudioGroups }
        if (next != null) {
            triedAudioGroups += next.index
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .setOverrideForType(TrackSelectionOverride(next.value.mediaTrackGroup, 0))
                .build()
            val f = next.value.getTrackFormat(0)
            val name = listOfNotNull(f.label, f.language, describe(f, C.TRACK_TYPE_AUDIO)).joinToString(" · ")
            return "The $what couldn't be decoded; switched to another audio track ($name)"
        }
        disable(C.TRACK_TYPE_AUDIO)
        return "Playing without sound: this video's $what can't be decoded on this phone"
    }

    private fun disable(type: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, true).build()
    }

    companion object {
        private const val MAX_ATTEMPTS = 4

        private val DECODER_ERRORS = setOf(
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        )

        /** The player's error, in words: which track failed, and its codec. */
        fun explain(e: PlaybackException, player: ExoPlayer): String {
            if (e is ExoPlaybackException && e.type == ExoPlaybackException.TYPE_RENDERER) {
                val type = runCatching { player.getRendererType(e.rendererIndex) }.getOrDefault(C.TRACK_TYPE_UNKNOWN)
                val what = describe(e.rendererFormat, type)
                return when (e.errorCode) {
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                        "This phone can't play this video's $what"
                    else -> "Can't play this video: its $what couldn't be decoded on this phone"
                }
            }
            return when (e.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Can't play this video: no connection to it"
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Can't play this video: the site refused it"
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Can't play this video: the file is gone"
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "Can't play this video: the file is damaged or in an unsupported format"
                else -> "Can't play this video (${e.errorCodeName})"
            }
        }

        /** "audio (DTS)", "video (HEVC 3840×2160)". */
        fun describe(format: Format?, trackType: Int): String {
            val kind = when (trackType) {
                C.TRACK_TYPE_AUDIO -> "audio"
                C.TRACK_TYPE_VIDEO -> "video"
                C.TRACK_TYPE_TEXT -> "subtitles"
                else -> "track"
            }
            val codec = codecName(format?.sampleMimeType) ?: return kind
            val size = if (trackType == C.TRACK_TYPE_VIDEO && format!!.width > 0) " ${format.width}×${format.height}" else ""
            return "$kind ($codec$size)"
        }

        fun codecName(mime: String?): String? = when (mime) {
            null -> null
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS"
            MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
            MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
            MimeTypes.AUDIO_AC3 -> "Dolby Digital"
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital Plus"
            MimeTypes.AUDIO_AC4 -> "Dolby AC-4"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_OPUS -> "Opus"
            MimeTypes.AUDIO_FLAC -> "FLAC"
            MimeTypes.VIDEO_H265 -> "HEVC"
            MimeTypes.VIDEO_H264 -> "H.264"
            MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
            MimeTypes.VIDEO_AV1 -> "AV1"
            MimeTypes.VIDEO_VP9 -> "VP9"
            else -> mime.substringAfter('/').uppercase()
        }
    }
}
