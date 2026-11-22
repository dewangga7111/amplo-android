package com.dewangga.amplo.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Player
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * The queue when the player last stopped, so it comes back after amplo closes: the songs by their
 * MediaStore id, the song that played and how far, the shuffle order, and the list they came from.
 */
data class SavedQueue(
    val ids: List<Long>,
    val index: Int,
    val positionMs: Long,
    /** The play order on shuffle, as indexes of [ids]. Empty when shuffle is off. */
    val shuffleOrder: List<Int> = emptyList(),
    val source: String = "",
    val sourceKind: String = "",
    val sourceKey: String = "",
) {
    fun toJson(): String = JSONObject()
        .put("ids", JSONArray(ids))
        .put("index", index)
        .put("positionMs", positionMs)
        .put("shuffleOrder", JSONArray(shuffleOrder))
        .put("source", source)
        .put("sourceKind", sourceKind)
        .put("sourceKey", sourceKey)
        .toString()

    /**
     * The queue without the songs that are gone, which [has] tells. The song that played stays
     * current when it is there, and its place is taken by the next song otherwise. Null when no
     * song is left.
     */
    fun keeping(has: (Long) -> Boolean): SavedQueue? {
        val kept = ids.indices.filter { has(ids[it]) }
        if (kept.isEmpty()) return null
        if (kept.size == ids.size) return this
        val current = kept.indexOfFirst { it >= index }.takeIf { it >= 0 } ?: 0
        val playedOn = kept.getOrNull(current) == index
        return copy(
            ids = kept.map(ids::get),
            index = current,
            positionMs = if (playedOn) positionMs else 0,
            // The order no longer fits the songs, so shuffle picks a new one.
            shuffleOrder = emptyList(),
        )
    }

    companion object {
        private const val FILE = "queue.json"

        fun fromJson(text: String): SavedQueue? = try {
            val json = JSONObject(text)
            val ids = json.getJSONArray("ids").let { array -> List(array.length()) { array.getLong(it) } }
            val order = json.optJSONArray("shuffleOrder")?.let { array -> List(array.length()) { array.getInt(it) } }.orEmpty()
            SavedQueue(
                ids = ids,
                index = json.optInt("index").coerceIn(0, (ids.size - 1).coerceAtLeast(0)),
                positionMs = json.optLong("positionMs").coerceAtLeast(0),
                // An order that is not of all the songs is left out.
                shuffleOrder = order.takeIf { it.sorted() == ids.indices.toList() }.orEmpty(),
                source = json.optString("source"),
                sourceKind = json.optString("sourceKind"),
                sourceKey = json.optString("sourceKey"),
            ).takeIf { ids.isNotEmpty() }
        } catch (_: Exception) {
            null
        }

        /** What [player] plays now, or null when its queue is empty. Call on the player's thread. */
        fun of(player: Player): SavedQueue? {
            val timeline = player.currentTimeline
            if (timeline.isEmpty || player.currentMediaItemIndex == C.INDEX_UNSET) return null
            val ids = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId.toLongOrNull() ?: return null }
            val order = ArrayList<Int>()
            if (player.shuffleModeEnabled) {
                var index = timeline.getFirstWindowIndex(true)
                while (index != C.INDEX_UNSET && order.size < ids.size) {
                    order += index
                    index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
                }
            }
            val metadata = player.playlistMetadata
            return SavedQueue(
                ids = ids,
                index = player.currentMediaItemIndex,
                positionMs = player.currentPosition.coerceAtLeast(0),
                shuffleOrder = order,
                source = metadata.title?.toString().orEmpty(),
                sourceKind = metadata.subtitle?.toString().orEmpty(),
                sourceKey = metadata.extras?.getString(SOURCE_KEY).orEmpty(),
            )
        }

        fun read(context: Context): SavedQueue? = try {
            File(context.filesDir, FILE).takeIf(File::isFile)?.readText()?.let(::fromJson)
        } catch (_: Exception) {
            null
        }

        /** Saves [queue], or forgets the saved one when it is null. */
        fun write(context: Context, queue: SavedQueue?) {
            val file = File(context.filesDir, FILE)
            try {
                if (queue == null) {
                    file.delete()
                    return
                }
                // A file that is half written must not replace the last good one.
                val part = File(context.filesDir, "$FILE.part")
                part.writeText(queue.toJson())
                if (!part.renameTo(file)) {
                    file.delete()
                    part.renameTo(file)
                }
            } catch (_: Exception) {
                // Losing the queue is not worth a message.
            }
        }
    }
}
