package az.chesshelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardStateTest {
    private val startPlacement =
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"

    @Test
    fun recognizesOnlyTheExactInitialPlacement() {
        assertTrue(BoardReader.isInitialPosition(startPlacement))
        assertFalse(BoardReader.isInitialPosition(
            "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR"
        ))
    }

    @Test
    fun removesOnlyTheCastlingRightForARookThatLeftItsHomeSquare() {
        val afterH1RookMoved =
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPR/RNBQKBN1"
        assertEquals("Q", BoardReader.updateCastling("KQ", startPlacement, afterH1RookMoved))
    }

    @Test
    fun removesBothRightsWhenKingLeavesItsHomeSquare() {
        val afterKingMoved =
            "rnbqkbnr/pppppppp/8/8/8/4K3/PPPP1PPP/RNBQ1BNR"
        assertEquals("-", BoardReader.updateCastling("KQ", startPlacement, afterKingMoved))
    }

    @Test
    fun lostCastlingRightsCannotBeRestoredByLaterBoardAppearance() {
        assertEquals("-", BoardReader.updateCastling("-", startPlacement, startPlacement))
    }
}
