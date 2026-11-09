package az.chesshelper

/**
 * Detects a rook that was deliberately moved onto a square and captured on the
 * opponent's immediately following move. A screenshot sequence cannot prove intent,
 * so this is a conservative, observable rook-sacrifice heuristic.
 */
object RookSacrificeDetector {
    fun wasRookSacrificed(
        beforeOfferPlacement: String,
        offeredPlacement: String,
        afterCapturePlacement: String,
        sideToMoveBeforeCapture: Char
    ): Boolean {
        if (sideToMoveBeforeCapture != 'w' && sideToMoveBeforeCapture != 'b') return false
        val before = parse(beforeOfferPlacement) ?: return false
        val offered = parse(offeredPlacement) ?: return false
        val after = parse(afterCapturePlacement) ?: return false
        val rook = if (sideToMoveBeforeCapture == 'b') 'R' else 'r'
        val capturerIsWhite = sideToMoveBeforeCapture == 'w'

        for (rank in 0..7) for (file in 0..7) {
            if (offered[rank][file] != rook || before[rank][file] == rook) continue
            val landed = after[rank][file]
            if (landed == '.' || landed.isUpperCase() != capturerIsWhite) continue

            // The rook must have moved here on the immediately preceding position change.
            var rookSources = 0
            for (fromRank in 0..7) for (fromFile in 0..7) {
                if (fromRank == rank && fromFile == file) continue
                if (before[fromRank][fromFile] == rook && offered[fromRank][fromFile] == '.') {
                    rookSources++
                }
            }
            if (rookSources != 1) continue

            // The opponent's capturing piece must have moved from a different square.
            var captureSources = 0
            for (fromRank in 0..7) for (fromFile in 0..7) {
                if (fromRank == rank && fromFile == file) continue
                if (offered[fromRank][fromFile] == landed && after[fromRank][fromFile] == '.') {
                    captureSources++
                }
            }
            if (captureSources == 1) return true
        }
        return false
    }

    private fun parse(placement: String): Array<CharArray>? {
        val ranks = placement.substringBefore(' ').split('/')
        if (ranks.size != 8) return null
        val board = Array(8) { CharArray(8) { '.' } }
        for (fenRank in 0..7) {
            var file = 0
            for (ch in ranks[fenRank]) {
                if (ch in '1'..'8') file += ch - '0'
                else if (ch in "pnbrqkPNBRQK") {
                    if (file !in 0..7) return null
                    board[7 - fenRank][file++] = ch
                } else return null
            }
            if (file != 8) return null
        }
        return board
    }
}
