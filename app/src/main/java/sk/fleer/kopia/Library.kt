package sk.fleer.kopia

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/**
 * Publishes a finished file into the system media library.
 *
 * Inserting through MediaStore needs no storage permission at all, and the file
 * is indexed by construction - so the bug the old Kivy app had (songs invisible
 * in music players until renamed, because nothing told MediaStore the file
 * existed) cannot happen here.
 */
object Library {

    fun publish(context: Context, source: File, media: Media, isVideo: Boolean): String {
        val name = filename(media.title, if (isVideo) "mp4" else "m4a")
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val folder = if (isVideo) "Movies/Kopia" else "Music/Kopia"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "audio/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.TITLE, media.title)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (!isVideo) {
                put(MediaStore.Audio.Media.ARTIST, media.author)
            }
            if (media.seconds > 0) {
                put(MediaStore.MediaColumns.DURATION, media.seconds * 1000)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Nepodarilo sa vytvoriť súbor v úložisku.")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out, 1 shl 16) }
            } ?: throw IOException("Nepodarilo sa otvoriť súbor na zápis.")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }

        resolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null)

        return "$folder/$name"
    }

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
