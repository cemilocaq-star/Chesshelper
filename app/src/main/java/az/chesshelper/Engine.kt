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
    private val closed = AtomicBoolean(false)
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
        send("uci")
        waitFor("uciok", 10_000)
        send("setoption name Threads value 2")
        send("isready")
        waitFor("readyok", 10_000)
    }

    @Synchronized
    private fun send(cmd: String) {
        check(!closed.get() && proc.isAlive) { "Stockfish prosesi aktiv deyil" }
        out.write(cmd)
        out.newLine()
        out.flush()
    }

    private fun nextLine(timeoutMs: Long): String {
        readerFailure?.let { throw IllegalStateException("Stockfish oxuma xətası", it) }
        return lines.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: throw IllegalStateException("Stockfish cavab vaxtını aşdı")
    }

    private fun waitFor(token: String, timeoutMs: Long) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1)
            if (nextLine(left).startsWith(token)) return
        }
    }

    /** Configures a limited ELO or unrestricted maximum strength. */
    @Synchronized
    fun configureStrength(elo: Int, maximum: Boolean = false) {
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
        send("position fen $fen")
        send("go movetime $moveTimeMs")
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(moveTimeMs + 5_000L)
        try {
            while (true) {
                val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (left <= 0) throw IllegalStateException("Stockfish analiz vaxtını aşdı")
                val line = nextLine(left)
                if (line.startsWith("bestmove")) {
                    val move = line.trim().split(Regex("\\s+")).getOrNull(1)
                    return move?.takeIf { it.matches(Regex("[a-h][1-8][a-h][1-8][qrbn]?")) }
                }
            }
        } catch (e: Exception) {
            try {
                send("stop")
                waitFor("bestmove", 2_000)
            } catch (_: Exception) {
                close()
            }
            return null
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
