package az.chesshelper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RookSacrificeDetectorTest {
    @Test
    fun detectsOpponentCapturingWhiteRook() {
        val before = "4k3/8/8/8/8/1n6/8/R3K3"
        val after = "4k3/8/8/8/8/8/8/n3K3"
        assertTrue(RookSacrificeDetector.wasRookCaptured(before, after, 'b'))
    }

    @Test
    fun detectsOpponentCapturingBlackRook() {
        val before = "r3k3/8/1N6/8/8/8/8/4K3"
        val after = "N3k3/8/8/8/8/8/8/4K3"
        assertTrue(RookSacrificeDetector.wasRookCaptured(before, after, 'w'))
    }

    @Test
    fun ordinaryRookMoveDoesNotTrigger() {
        val before = "4k3/8/8/8/8/8/8/R3K3"
        val after = "4k3/8/8/8/8/8/R7/4K3"
        assertFalse(RookSacrificeDetector.wasRookCaptured(before, after, 'w'))
    }

    @Test
    fun disappearanceWithoutCapturingPieceDoesNotTrigger() {
        val before = "4k3/8/8/8/8/8/8/R3K3"
        val after = "4k3/8/8/8/8/8/8/4K3"
        assertFalse(RookSacrificeDetector.wasRookCaptured(before, after, 'b'))
    }
}
