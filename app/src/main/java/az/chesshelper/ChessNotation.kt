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
        val parsed = Move(uci, board.getSideToMove())
        // isMoveLegal checks king safety and move consistency, but the complete legal move
        // generator is the authoritative check for piece geometry and blocked paths.
        val move = board.legalMoves().firstOrNull { it == parsed }
            ?: throw IllegalArgumentException("Mühərrik qanunsuz gediş qaytardı: $uci")
        val list = MoveList(fen)
        require(list.add(move)) { "Gediş tarixçəyə əlavə edilmədi" }
        list.toSanArray().singleOrNull()
            ?: throw IllegalStateException("SAN çevirməsi alınmadı")
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
