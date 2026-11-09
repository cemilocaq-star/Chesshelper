package az.chesshelper

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveList

/**
 * Validates an engine UCI response against its exact root FEN and converts it to SAN.
 * A recommendation is not treated as a move that the user actually played.
 */
object ChessNotation {
    private val uciPattern = Regex("[a-h][1-8][a-h][1-8][qrbn]?")

    fun sanFor(fen: String, uci: String): String? = runCatching {
        require(uciPattern.matches(uci)) { "UCI formatı yanlışdır" }
        val board = Board()
        board.loadFromFen(fen)
        val move = Move(uci, board.getSideToMove())
        require(board.isMoveLegal(move, true)) { "Mühərrik qanunsuz gediş qaytardı: $uci" }
        val list = MoveList(fen)
        require(list.add(move)) { "Gediş tarixçəyə əlavə edilmədi" }
        val san = list.toSanArray().singleOrNull()
            ?: throw IllegalStateException("SAN çevirməsi alınmadı")
        val verifier = board.clone()
        require(verifier.doMove(move, true)) { "Gediş təsdiqlənmədi" }
        san
    }.getOrNull()

    fun sanWithMoveNumber(fen: String, uci: String): String? = runCatching {
        require(sanFor(fen, uci) != null) { "Gediş qanuni deyil" }
        val board = Board().apply { loadFromFen(fen) }
        val move = Move(uci, board.getSideToMove())
        val list = MoveList(fen)
        list.add(move)
        list.toSanWithMoveNumbers()
    }.getOrNull()
}
