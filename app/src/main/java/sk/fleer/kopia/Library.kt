package sk.fleer.kopia

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/**
 * Puts finished files into the system media library, and knows what is already
 * there so that downloading a playlist again fetches only what is new.
 *
 * Inserting through MediaStore needs no storage permission, and the file is
 * indexed by construction - so the old Kivy app's bug (songs invisible in music
 * players until renamed) cannot happen here.
 *
 * Saving is split into claim / fill / abandon so the existence check can happen
 * *before* anything is downloaded.
 */
object Library {

    class Slot(val uri: Uri, val folder: String)

    sealed interface Claim {
        class Free(val slot: Slot) : Claim
        class Taken(val path: String) : Claim
    }

    /** One record per video and mode: a different video quality is a different file. */
    fun key(videoId: String, height: Int) = if (height > 0) "video:$height:$videoId" else "audio:$videoId"

    /**
     * The file this video was saved to earlier, if it still exists. Works even
     * when its name has changed meanwhile (e.g. its playlist number shifted).
     */
    fun known(context: Context, key: String): String? {
        val records = records(context)
        val uri = records.getString(key, null)?.let(Uri::parse) ?: return null
        val path = context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME),
            null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0).trimEnd('/') + "/" + c.getString(1) else null }
        if (path == null) records.edit().remove(key).apply()  // deleted by the user: fetch it again
        return path
    }

    /**
     * Checks whether the file name is free before downloading. When a file of
     * that name already exists, MediaStore does not refuse - it renames the new
     * entry to "name (1)". That rename is the existence check, and it also covers
     * files this app can no longer see (left from before a reinstall: MediaStore
     * drops their owner, and without a storage permission an app sees only what
     * it owns).
     *
     * Measured on the user's Pixel 7: the rename happens at once for a finished
     * (non-pending) entry, but a *pending* entry keeps the requested name until
     * it is finished - so probing with the pending entry itself always looked
     * free and produced the very duplicate this is meant to prevent. Hence a
     * short-lived non-pending probe first, then the real pending entry.
     */
    fun claim(
        context: Context,
        media: Media,
        isVideo: Boolean,
        album: String? = null,
        track: Int? = null,
    ): Claim {
        // Numbering the filename keeps album tracks in order even in file
        // managers and players that ignore the TRACK column.
        val prefix = track?.let { "%02d - ".format(it) }.orEmpty()
        val name = filename(prefix + media.title, if (isVideo) "mp4" else "m4a")
        val folder = folderFor(isVideo, album)
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "audio/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            // Artist, album and track number are not set here: MediaStore re-reads
            // them from the file once it is finished, so they go into the file
            // itself (Mp4Tags).
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        // Where an earlier copy may already be: the plain name covers a song
        // downloaded on its own (seen on the user's phone - the same file saved
        // twice under the two names), and the base folder covers singles and
        // playlists synced before they got their own folder. The base folder is
        // probed first: probing inside the playlist folder creates it, and it
        // should not be left behind empty when the song turns up elsewhere.
        val plain = filename(media.title, if (isVideo) "mp4" else "m4a")
        val base = folderFor(isVideo, null)
        val places = listOf(base to name, base to plain, folder to name, folder to plain).distinct()
        for ((where, candidate) in places) {
            val probe = resolver.insert(collection, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, candidate)
                put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "audio/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, where)
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }) ?: throw IOException("Nepodarilo sa vytvoriť súbor v úložisku.")
            val given = displayName(context, probe)
            resolver.delete(probe, null, null)
            if (given != candidate) return Claim.Taken("$where/$candidate")
        }

        val uri = resolver.insert(collection, values)
            ?: throw IOException("Nepodarilo sa vytvoriť súbor v úložisku.")
        return Claim.Free(Slot(uri, folder))
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    /** Writes the file and returns where it really ended up - read back, not assumed. */
    fun fill(context: Context, slot: Slot, source: File, key: String): String {
        val resolver = context.contentResolver
        resolver.openOutputStream(slot.uri)?.use { out ->
            source.inputStream().use { it.copyTo(out, 1 shl 16) }
        } ?: throw IOException("Nepodarilo sa otvoriť súbor na zápis.")
        resolver.update(slot.uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null)
        records(context).edit().putString(key, slot.uri.toString()).apply()
        return slot.folder + "/" + (displayName(context, slot.uri) ?: "?")
    }

    fun abandon(context: Context, slot: Slot) {
        runCatching { context.contentResolver.delete(slot.uri, null, null) }
    }

    /** A playlist gets its own folder: Music/Kopia/<playlist>, singles stay in Music/Kopia. */
    fun folderFor(isVideo: Boolean, playlist: String?): String {
        val base = if (isVideo) "Movies/Kopia" else "Music/Kopia"
        val sub = playlist
            ?.replace(Regex("""[/\\:*?"<>|]"""), " ")  // "Rock/Pop" -> "Rock Pop", not "RockPop"
            ?.replace(Regex("""\s+"""), " ")
            ?.trim(' ', '.')  // no hidden folders, no "." or ".." path tricks
            ?.take(100)
            ?.ifBlank { null }
        return if (sub == null) base else "$base/$sub"
    }

    private fun records(context: Context) =
        context.getSharedPreferences("library", Context.MODE_PRIVATE)

    private fun filename(title: String, extension: String): String {
        val safe = title
            .replace(Regex("""[/\\:*?"<>|]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(120)
            .ifBlank { "Kopia" }
        return "$safe.$extension"
    }
}
