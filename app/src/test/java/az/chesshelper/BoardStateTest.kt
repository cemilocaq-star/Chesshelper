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

    @Test
    fun whiteBottomSquareCentersMatchScreenCoordinates() {
        val board = BoardRect(0, 458, 691)
        val a1 = BoardReader.center(board, "a1", true)
        val h8 = BoardReader.center(board, "h8", true)
        val square = 691f / 8f
        assertEquals(square / 2f, a1.first, 0.01f)
        assertEquals(458f + 7.5f * square, a1.second, 0.01f)
        assertEquals(7.5f * square, h8.first, 0.01f)
        assertEquals(458f + square / 2f, h8.second, 0.01f)
    }

    @Test
    fun blackBottomSquareCentersRotateBothAxes() {
        val board = BoardRect(0, 458, 691)
        val a1 = BoardReader.center(board, "a1", false)
        val h8 = BoardReader.center(board, "h8", false)
        val square = 691f / 8f
        assertEquals(7.5f * square, a1.first, 0.01f)
        assertEquals(458f + square / 2f, a1.second, 0.01f)
        assertEquals(square / 2f, h8.first, 0.01f)
        assertEquals(458f + 7.5f * square, h8.second, 0.01f)
    }


    @Test
    fun initialPositionDefaultsToWhiteToMoveWithoutLastMoveHighlights() {
        assertEquals('w', BoardReader.defaultSideToMove(start))
    }

    @Test
    fun nonInitialPositionDoesNotGuessSideToMoveWithoutHighlights() {
        val afterE4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR"
        assertEquals(null, BoardReader.defaultSideToMove(afterE4))
    }

}
