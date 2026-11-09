package az.chesshelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChessNotationTest {
    private val start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    @Test
    fun convertsInitialWhitePawnMoveToSan() {
        assertEquals("e4", ChessNotation.sanFor(start, "e2e4"))
    }

    @Test
    fun rejectsIllegalEngineMove() {
        assertNull(ChessNotation.sanFor(start, "e2e5"))
    }

    @Test
    fun handlesBlackToMoveFen() {
        val afterE4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
        assertEquals("e5", ChessNotation.sanFor(afterE4, "e7e5"))
    }

    @Test
    fun convertsKingSideCastle() {
        val openBoard = "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1"
        assertEquals("O-O", ChessNotation.sanFor(openBoard, "e1g1"))
    }

    @Test
    fun rejectsMalformedUci() {
        assertNull(ChessNotation.sanFor(start, "e2e"))
    }
    @Test
    fun formatsWhiteRecommendationWithMoveNumber() {
        assertEquals("1. e4", ChessNotation.sanWithMoveNumber(start, "e2e4"))
    }

    @Test
    fun formatsBlackRecommendationWithEllipsis() {
        val afterE4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
        assertEquals("1... e5", ChessNotation.sanWithMoveNumber(afterE4, "e7e5"))
    }

}
