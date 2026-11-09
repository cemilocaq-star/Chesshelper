package az.chesshelper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RookSacrificeDetectorTest {
    @Test
    fun detectsWhiteRookOfferedAndCapturedOnOpponentsNextMove() {
        val beforeOffer = "4k3/8/8/8/1n6/8/8/R3K3"
        val offered = "4k3/8/8/8/1n6/8/R7/4K3"
        val captured = "4k3/8/8/8/1n6/8/n7/4K3"
        assertTrue(RookSacrificeDetector.wasRookSacrificed(beforeOffer, offered, captured, 'b'))
    }

    @Test
    fun detectsBlackRookOfferedAndCapturedOnOpponentsNextMove() {
        val beforeOffer = "r3k3/8/8/1N6/8/8/8/4K3"
        val offered = "4k3/r7/8/1N6/8/8/8/4K3"
        val captured = "4k3/N7/8/8/8/8/8/4K3"
        assertTrue(RookSacrificeDetector.wasRookSacrificed(beforeOffer, offered, captured, 'w'))
    }

    @Test
    fun ordinaryRookMoveWithoutCaptureDoesNotTrigger() {
        val beforeOffer = "4k3/8/8/8/8/8/8/R3K3"
        val offered = "4k3/8/8/8/8/8/R7/4K3"
        val unchanged = offered
        assertFalse(RookSacrificeDetector.wasRookSacrificed(beforeOffer, offered, unchanged, 'b'))
    }

    @Test
    fun captureOfRookThatWasNotJustOfferedDoesNotTrigger() {
        val beforeOffer = "4k3/8/8/8/8/8/8/R3K3"
        val offered = beforeOffer
        val captured = "4k3/8/8/8/8/8/8/n3K3"
        assertFalse(RookSacrificeDetector.wasRookSacrificed(beforeOffer, offered, captured, 'b'))
    }
}
