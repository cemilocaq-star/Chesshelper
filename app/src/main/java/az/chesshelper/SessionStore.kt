package az.chesshelper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class MoveRecord(
    val fen: String,
    val uci: String,
    val timestamp: Long,
    val confidence: Int,
    val recognitionMs: Long,
    val engineMs: Long
)

object SessionStore {
    private const val PREFS = "chess"
    private const val KEY_HISTORY = "move_history_v1"
    private const val LIMIT = 100

    @Synchronized
    fun append(context: Context, record: MoveRecord) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val history = read(context).toMutableList()
        history.add(record)
        while (history.size > LIMIT) history.removeAt(0)
        val array = JSONArray()
        history.forEach { item ->
            array.put(JSONObject().apply {
                put("fen", item.fen)
                put("uci", item.uci)
                put("timestamp", item.timestamp)
                put("confidence", item.confidence)
                put("recognitionMs", item.recognitionMs)
                put("engineMs", item.engineMs)
            })
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    fun read(context: Context): List<MoveRecord> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                MoveRecord(
                    fen = o.optString("fen"),
                    uci = o.optString("uci"),
                    timestamp = o.optLong("timestamp"),
                    confidence = o.optInt("confidence"),
                    recognitionMs = o.optLong("recognitionMs"),
                    engineMs = o.optLong("engineMs")
                )
            }.takeLast(LIMIT)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Keeps coordinate notation verbatim; it does not invent SAN moves. */
    fun toPgn(context: Context): String {
        val items = read(context)
        val date = java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.US)
            .format(java.util.Date())
        return buildString {
            appendLine("[Event \"ChessHelper analysis\"]")
            appendLine("[Site \"Local device\"]")
            appendLine("[Date \"$date\"]")
            appendLine("[Result \"*\"]")
            appendLine()
            items.forEachIndexed { index, item ->
                appendLine("{ ply=${index + 1}; UCI=${item.uci}; FEN=${item.fen}; confidence=${item.confidence}%; recognition=${item.recognitionMs}ms; engine=${item.engineMs}ms }")
            }
            appendLine("*")
        }
    }

    fun toUci(context: Context): String = buildString {
        read(context).forEach { item ->
            append(item.uci)
            append('\n')
        }
    }
}
