package az.chesshelper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class MoveRecord(
    val fen: String,
    val uci: String,
    val timestamp: Long,
    val pieceCoverage: Int,
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
                put("pieceCoverage", item.pieceCoverage)
                put("recognitionMs", item.recognitionMs)
                put("engineMs", item.engineMs)
            })
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    @Synchronized
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
                    pieceCoverage = o.optInt("pieceCoverage", o.optInt("confidence")),
                    recognitionMs = o.optLong("recognitionMs"),
                    engineMs = o.optLong("engineMs")
                )
            }.takeLast(LIMIT)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Exports every engine recommendation as its own valid one-move PGN game rooted at its FEN.
     * These are independent suggestions, not a fabricated record of moves actually played.
     */
    fun toPgn(context: Context): String {
        val items = read(context)
        val date = java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.US)
            .format(java.util.Date())
        return buildString {
            items.forEachIndexed { index, item ->
                val san = ChessNotation.sanWithMoveNumber(item.fen, item.uci) ?: return@forEachIndexed
                if (isNotEmpty()) appendLine().appendLine()
                appendLine("[Event \"ChessHelper engine recommendation ${index + 1}\"]")
                appendLine("[Site \"Local device\"]")
                appendLine("[Date \"$date\"]")
                appendLine("[SetUp \"1\"]")
                appendLine("[FEN \"${item.fen}\"]")
                appendLine("[Result \"*\"]")
                appendLine()
                append(san)
                append(" {UCI=${item.uci}; strong-piece-recognition=${item.pieceCoverage}%; recognition=${item.recognitionMs}ms; engine=${item.engineMs}ms} *")
                appendLine()
            }
        }
    }

    /** Coordinate notation export; every UCI move is associated with its root FEN comment. */
    fun toUci(context: Context): String = buildString {
        read(context).forEach { item ->
            appendLine("# FEN ${item.fen}")
            appendLine(item.uci)
            appendLine()
        }
    }
}
