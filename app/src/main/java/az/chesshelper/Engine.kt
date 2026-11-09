package az.chesshelper

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Stockfish UCI adapter with bounded waits so a stalled engine cannot block analysis forever. */
class Engine(ctx: Context) : AutoCloseable {
    private val proc: Process
    private val out: BufferedWriter
    private val lines = LinkedBlockingQueue<String>()
    private val outputLock = Any()
    @Volatile private var searching = false
    private val closed = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    @Volatile private var readerFailure: Throwable? = null

    init {
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libstockfish.so")
        if (!bin.exists()) throw IllegalStateException("libstockfish.so yoxdur (README-yə bax)")
        proc = ProcessBuilder(bin.path).redirectErrorStream(true).start()
        out = proc.outputStream.bufferedWriter()
        val input = proc.inputStream.bufferedReader()
        Thread({
            try {
                while (!closed.get()) {
                    val line = input.readLine() ?: break
                    lines.offer(line)
                }
                if (!closed.get()) readerFailure = IllegalStateException("Stockfish dayandı")
            } catch (t: Throwable) {
                if (!closed.get()) readerFailure = t
            }
        }, "Stockfish-UCI-reader").apply { isDaemon = true; start() }
        try {
            send("uci")
            waitFor("uciok", 10_000)
            send("setoption name Threads value 2")
            send("isready")
            waitFor("readyok", 10_000)
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private fun send(cmd: String) {
        synchronized(outputLock) {
            sendLocked(cmd)
        }
    }

    private fun sendLocked(cmd: String) {
        check(!closed.get() && proc.isAlive) { "Stockfish prosesi aktiv deyil" }
        out.write(cmd)
        out.newLine()
        out.flush()
    }

    /** Interrupts a search or marks a pending UCI operation as cancelled. */
    fun resetCancellation() {
        stopRequested.set(false)
    }

    /** Interrupts the active search without waiting for best()'s response lock. */
    fun stopSearch() {
        stopRequested.set(true)
        if (!searching || closed.get()) return
        try {
            synchronized(outputLock) {
                if (searching && !closed.get() && proc.isAlive) {
                    out.write("stop")
                    out.newLine()
                    out.flush()
                }
            }
        } catch (_: Exception) {
            close()
        }
    }

    private fun nextLine(timeoutMs: Long, allowStopRequested: Boolean = false): String {
        if (!allowStopRequested && stopRequested.get()) {
            throw java.util.concurrent.CancellationException("Stockfish əməliyyatı dayandırıldı")
        }
        readerFailure?.let { throw IllegalStateException("Stockfish oxuma xətası", it) }
        return lines.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: throw IllegalStateException("Stockfish cavab vaxtını aşdı")
    }

    private fun waitFor(token: String, timeoutMs: Long, allowStopRequested: Boolean = false) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1)
            if (nextLine(left, allowStopRequested).startsWith(token)) return
        }
    }

    /** Configures a limited ELO or unrestricted maximum strength. */
    @Synchronized
    fun configureStrength(elo: Int, maximum: Boolean = false) {
        if (stopRequested.get()) {
            throw java.util.concurrent.CancellationException("Stockfish əməliyyatı dayandırıldı")
        }
        if (maximum) {
            send("setoption name UCI_LimitStrength value false")
        } else {
            send("setoption name UCI_LimitStrength value true")
            send("setoption name UCI_Elo value ${elo.coerceIn(1320, 3000)}")
        }
        send("isready")
        waitFor("readyok", 5_000)
    }

    /** Returns a UCI move, or null for a terminal position / engine timeout. */
    @Synchronized
    fun best(fen: String, moveTimeMs: Int = 800): String? {
        require(moveTimeMs in 100..30_000) { "Analiz müddəti 100–30000 ms olmalıdır" }
        require(fen.isNotBlank() && !fen.contains('\n')) { "FEN yanlışdır" }
        if (stopRequested.get()) throw java.util.concurrent.CancellationException("Stockfish əməliyyatı dayandırıldı")
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(moveTimeMs + 5_000L)
        try {
            synchronized(outputLock) {
                check(!closed.get() && proc.isAlive) { "Stockfish prosesi aktiv deyil" }
                if (stopRequested.get()) throw java.util.concurrent.CancellationException("Stockfish əməliyyatı dayandırıldı")
                sendLocked("position fen $fen")
                searching = true
                sendLocked("go movetime $moveTimeMs")
            }
            while (true) {
                val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (left <= 0) throw IllegalStateException("Stockfish analiz vaxtını aşdı")
                val line = nextLine(left)
                if (line.startsWith("bestmove")) {
                    val move = line.trim().split(Regex("\\s+")).getOrNull(1)
                    return move?.takeIf { it.matches(Regex("[a-h][1-8][a-h][1-8][qrbn]?")) }
                }
            }
        } catch (e: java.util.concurrent.CancellationException) {
            recoverAfterFailedSearch()
            throw e
        } catch (e: Exception) {
            recoverAfterFailedSearch()
            throw e
        } finally {
            searching = false
        }
    }

    /**
     * Stop a failed search and consume its terminal bestmove so the next UCI command
     * cannot accidentally read a stale response. If recovery fails, retire the process.
     */
    private fun recoverAfterFailedSearch() {
        try {
            stopSearch()
            waitFor("bestmove", 2_000, allowStopRequested = true)
        } catch (_: Exception) {
            close()
        }
    }

    /** Closing is intentionally not synchronized with best(): destroy() must unblock a stalled read. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            proc.destroy()
            if (proc.isAlive) proc.destroyForcibly()
        } catch (_: Exception) {
            try { proc.destroyForcibly() } catch (_: Exception) { }
        }
    }
}
