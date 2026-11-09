package az.chesshelper

/**
 * Detects a rook being captured between two consecutive recognized board positions.
 * This is an observable proxy for a rook sacrifice; screenshots alone cannot prove
 * that the preceding move was strategically intentional.
 */
object RookSacrificeDetector {
    fun wasRookCaptured(previousPlacement: String, currentPlacement: String, sideToMoveBefore: Char): Boolean {
        if (sideToMoveBefore != 'w' && sideToMoveBefore != 'b') return false
        val before = parse(previousPlacement) ?: return false
        val after = parse(currentPlacement) ?: return false
        val victimRook = if (sideToMoveBefore == 'w') 'r' else 'R'
        val capturerIsWhite = sideToMoveBefore == 'b'

        for (rank in 0..7) for (file in 0..7) {
            if (before[rank][file] != victimRook) continue
            val landed = after[rank][file]
            if (landed == '.' || landed.isUpperCase() != capturerIsWhite) continue

            // Require a source square to become empty and the same attacking piece
            // to land on the rook square. This filters ordinary rook moves/disappearances.
            var matchingSources = 0
            for (fromRank in 0..7) for (fromFile in 0..7) {
                if (fromRank == rank && fromFile == file) continue
                if (before[fromRank][fromFile] == landed &&
                    after[fromRank][fromFile] == '.') matchingSources++
            }
            if (matchingSources == 1) return true
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
                if (ch in '1'..'8') {
                    file += ch - '0'
                } else if (ch in "pnbrqkPNBRQK") {
                    if (file !in 0..7) return null
                    board[7 - fenRank][file++] = ch
                } else return null
            }
            if (file != 8) return null
        }
        return board
    }
}
