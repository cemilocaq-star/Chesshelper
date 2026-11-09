package az.chesshelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardReaderRecognitionTest {
    private val pieceOrder = "KQRBNPkqrbnp"
    private val templates: Templates = Templates(
        pieceOrder.mapIndexed { index, piece ->
            piece to listOf(Sample(feature(index), feature(index)))
        }.toMap(),
        120f,
        200f
    )

    private fun feature(index: Int): FloatArray =
        FloatArray(256).also { it[index] = 1f }

    /**
     * Builds a pixel-independent Scan with a perfect, distinct silhouette template per piece.
     * This isolates BoardReader's board mapping and position constraints from Android Bitmap APIs.
     */
    private fun readPosition(
        placement: String,
        whiteBottom: Boolean = true,
        lastMove: String? = null
    ): Reading? {
        val board = Array(8) { CharArray(8) { '.' } }
        val ranks = placement.split("/")
        require(ranks.size == 8)
        ranks.forEachIndexed { row, text ->
            var file = 0
            text.forEach { ch ->
                if (ch in '1'..'8') {
                    file += ch - '0'
                } else {
                    require(file < 8)
                    board[row][file++] = ch
                }
            }
            require(file == 8)
        }

        val screen = if (whiteBottom) board else Array(8) { row ->
            CharArray(8) { col -> board[7 - row][7 - col] }
        }
        val info = Array(8) { row ->
            Array(8) { col ->
                val piece = screen[row][col]
                if (piece == '.') {
                    SquareInfo(FloatArray(256), FloatArray(256), 0f, false, 0.6f, intArrayOf(0, 0, 0))
                } else {
                    val index = pieceOrder.indexOf(piece)
                    require(index >= 0)
                    SquareInfo(
                        feature(index),
                        feature(index),
                        if (piece.isUpperCase()) 230f else 20f,
                        true,
                        0.6f,
                        intArrayOf(120, 120, 120)
                    )
                }
            }
        }
        val highlights = Array(8) { BooleanArray(8) }
        if (lastMove != null) {
            require(lastMove.length >= 4)
            fun screenSquare(fileChar: Char, rankChar: Char): Pair<Int, Int> {
                val file = fileChar - 'a'
                val rank = rankChar - '1'
                val row = 7 - rank
                return if (whiteBottom) row to file else (7 - row) to (7 - file)
            }
            val from = screenSquare(lastMove[0], lastMove[1])
            val to = screenSquare(lastMove[2], lastMove[3])
            highlights[from.first][from.second] = true
            highlights[to.first][to.second] = true
        }
        return BoardReader.read(
            Scan(BoardRect(0, 0, 800), info, highlights),
            templates,
            whiteBottom,
            detectOrientation = true
        )
    }

    @Test
    fun readsTheInitialPositionAndDefaultsToWhiteToMove() {
        val start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
        val result = readPosition(start)
        assertEquals(start, result?.placement)
        assertEquals('w', result?.sideToMove)
        assertEquals("-", result?.enPassant)
        assertEquals(32, result?.occupiedCount)
    }

    @Test
    fun recognizesWhiteDoublePawnMoveAndBlackToMove() {
        val afterE4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR"
        val result = readPosition(afterE4, lastMove = "e2e4")
        assertEquals(afterE4, result?.placement)
        assertEquals('b', result?.sideToMove)
        assertEquals("e3", result?.enPassant)
    }

    @Test
    fun recognizesBlackDoublePawnMoveAndEnPassantTarget() {
        val afterD5 = "rnbqkbnr/ppp1pppp/8/3p4/4P3/8/PPPP1PPP/RNBQKBNR"
        val result = readPosition(afterD5, lastMove = "d7d5")
        assertEquals(afterD5, result?.placement)
        assertEquals('w', result?.sideToMove)
        assertEquals("d6", result?.enPassant)
    }

    @Test
    fun readsARealCaptureWithoutRestoringTheCapturedPiece() {
        val afterCapture = "4k3/8/8/3P4/8/8/8/4K3"
        val result = readPosition(afterCapture, lastMove = "e4d5")
        assertEquals(afterCapture, result?.placement)
        assertEquals('b', result?.sideToMove)
        assertEquals("-", result?.enPassant)
        assertEquals(3, result?.occupiedCount)
    }

    @Test
    fun readsThePositionAfterKingSideCastling() {
        val afterCastle = "r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQ1RK1"
        val result = readPosition(afterCastle, lastMove = "e1g1")
        assertEquals(afterCastle, result?.placement)
        assertEquals('b', result?.sideToMove)
        assertEquals('K', BoardReader.pieceAt(result?.placement ?: "", "g1"))
        assertEquals('R', BoardReader.pieceAt(result?.placement ?: "", "f1"))
    }

    @Test
    fun readsAPromotedQueenWithoutInventingOtherPieces() {
        val afterPromotion = "Q3k3/8/8/8/8/8/8/4K3"
        val result = readPosition(afterPromotion, lastMove = "a7a8")
        assertEquals(afterPromotion, result?.placement)
        assertEquals('b', result?.sideToMove)
        assertEquals('Q', BoardReader.pieceAt(result?.placement ?: "", "a8"))
        assertEquals(3, result?.occupiedCount)
    }

    @Test
    fun mapsBlackAtBottomByRotatingBothAxes() {
        val start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
        val result = readPosition(start, whiteBottom = false)
        assertEquals(start, result?.placement)
        assertFalse(result?.whiteBottom ?: true)
    }

    @Test
    fun doesNotInventEnPassantForAPawnThatMovedTwoSquaresFromTheWrongRank() {
        val position = "4k3/8/8/4P3/8/8/8/4K3"
        val result = readPosition(position, lastMove = "e3e5")
        assertEquals(position, result?.placement)
        assertEquals('b', result?.sideToMove)
        assertEquals("-", result?.enPassant)
    }

    @Test
    fun rejectsAPositionWithoutARealWhiteKingInsteadOfFabricatingOne() {
        val missingKing = "4k3/8/8/8/8/8/8/R3R3"
        assertNull(readPosition(missingKing))
    }
}
