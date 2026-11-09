package az.chesshelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardStateTest {
    private val start =
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"

    @Test
    fun detectsExactInitialPlacement() {
        assertTrue(BoardReader.isInitialPosition(start))
        assertFalse(BoardReader.isInitialPosition(
            "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR"
        ))
    }

    @Test
    fun removesOnlyTheRightForTheRookThatLeftItsHomeSquare() {
        val rookOnH2 =
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPR/RNBQKBN1"
        assertEquals("Q", BoardReader.updateCastling("KQ", start, rookOnH2))
    }

    @Test
    fun removesBothRightsWhenKingLeavesHomeSquare() {
        val kingOnE3 =
            "rnbqkbnr/pppppppp/8/8/8/4K3/PPPP1PPP/RNBQ1BNR"
        assertEquals("-", BoardReader.updateCastling("KQ", start, kingOnE3))
    }

    @Test
    fun castlingRightsCannotBeRestoredAfterTheyWereLost() {
        assertEquals("-", BoardReader.updateCastling("-", start, start))
    }
    @Test
    fun calibrationLayoutUsesNormalFileOrderWhenWhiteIsAtBottom() {
        val layout = BoardReader.initialLayoutForScreen(true)
        assertEquals("rnbqkbnr", layout[0])
        assertEquals("RNBQKBNR", layout[7])
    }

    @Test
    fun calibrationLayoutReversesFilesWhenBlackIsAtBottom() {
        val layout = BoardReader.initialLayoutForScreen(false)
        assertEquals("RNBKQBNR", layout[0])
        assertEquals("rnbkqbnr", layout[7])
    }

}
