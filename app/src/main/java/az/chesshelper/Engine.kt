package az.chesshelper

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File

/** Stockfish-i UCI protokolu ilə idarə edir (libstockfish.so jniLibs-dən gəlir). */
class Engine(ctx: Context) {

    private val proc: Process
    private val out: BufferedWriter
    private val inp: BufferedReader

    init {
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libstockfish.so")
        if (!bin.exists()) throw IllegalStateException("libstockfish.so yoxdur (README-yə bax)")
        proc = ProcessBuilder(bin.path).redirectErrorStream(true).start()
        out = proc.outputStream.bufferedWriter()
        inp = proc.inputStream.bufferedReader()
        send("uci")
        waitFor("uciok")
        send("setoption name Threads value 2")
        send("isready")
        waitFor("readyok")
    }

    private fun send(cmd: String) {
        out.write(cmd)
        out.write("\n")
        out.flush()
    }

    private fun waitFor(token: String) {
        while (true) {
            val line = inp.readLine() ?: throw IllegalStateException("Stockfish dayandı")
            if (line.startsWith(token)) return
        }
    }

    /** Stockfish-i seçilmiş ELO ilə məhdudlaşdırır və ya maksimum gücdə saxlayır. */
    @Synchronized
    fun configureStrength(elo: Int, maximum: Boolean = false) {
        if (maximum) {
            send("setoption name UCI_LimitStrength value false")
        } else {
            val safeElo = elo.coerceIn(1320, 3000)
            send("setoption name UCI_LimitStrength value true")
            send("setoption name UCI_Elo value $safeElo")
        }
        send("isready")
        waitFor("readyok")
    }

    /** FEN üçün ən yaxşı gedişi qaytarır, məsələn "e2e4". */
    @Synchronized
    fun best(fen: String, moveTimeMs: Int = 800): String? {
        send("position fen $fen")
        send("go movetime $moveTimeMs")
        while (true) {
            val line = inp.readLine() ?: return null
            if (line.startsWith("bestmove")) {
                val m = line.split(" ").getOrNull(1)
                return if (m == null || m == "(none)" || m.length < 4) null else m
            }
        }
    }

    fun close() {
        try {
            send("quit")
        } catch (_: Exception) {
        }
        proc.destroy()
    }
}
