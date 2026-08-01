package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable

/**
 * One selectable audio rendition of the media currently open — the bilingual channel's French and
 * English streams, a director's commentary, an audio-description track.
 *
 * Deliberately shaped like [SubtitleTrack]: a human [label], the [language] it carries, and a
 * backend-specific handle. [SubtitleTrack] calls that handle `src` because a subtitle can be an
 * external file; an audio rendition is always embedded, so the handle is the [id] the backend knows
 * it by (libVLC's elementary-stream id, an `AVMediaSelectionOption` index, a Media3 track index).
 * Two differently shaped track types in the same interface would be gratuitous inconsistency.
 *
 * [id] is opaque to callers: pass a track back to
 * [VideoPlayerState.selectAudioTrack] as it came out of [VideoPlayerState.availableAudioTracks],
 * never one built by hand.
 */
@Stable
data class AudioTrack(
    val label: String,
    val language: String,
    val id: String,
)
