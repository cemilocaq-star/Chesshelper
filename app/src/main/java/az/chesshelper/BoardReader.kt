package az.chesshelper

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Alqoritm əvvəlcə Python-da sintetik taxtalarla yoxlanılıb (4 mövzu, 2 fiqur dəsti, sürüşmə,
// bulanıqlıq, şah hücumu parıltısı, tutma/gediş işarələri) və buraya eyni sabitlərlə köçürülüb.

private const val F = 16                 // xana mərkəzi 16x16 xüsusiyyət şəbəkəsinə bölünür
private const val FLEN = F * F
private const val WALL_T = 70            // fondan fərq (RGB cəmi) bu həddi keçəndə piksel "fon deyil"
private const val MIN_COVER = 0.15f      // xananın dolu sayılması üçün maska payı
private const val MIN_BBOX = 0.45f       // və maskanın en/hündürlük payı (gediş nöqtəsini ayırır)
private const val PIECES = "KQRBNPkqrbnp"

/** Bu balın altında oxunan xana "şübhəli" sayılır. */
const val WEAK_SCORE = 0.60f

data class BoardRect(val x: Int, val y: Int, val size: Int)

/** Bir fiqurun nümunəsi: kənar xəritəsi və siluet maskası (ikisi də L2-normallaşdırılmış). */
class Sample(val edges: FloatArray, val mask: FloatArray)

class SquareInfo(
    val edges: FloatArray,
    val mask: FloatArray,
    /** Fiqurun dolgu parlaqlığı (maska pikselləri üzrə median). */
    val fill: Float,
    val occupied: Boolean,
    /** Maska xananın çox hissəsini tutubsa (şah parıltısı) kənar xəritəsinə daha çox güvənilir. */
    val maskWeight: Float,
    val bg: IntArray
)

/** Bir ekran görüntüsündən oxunmuş 8x8 xana məlumatı (ekran koordinatlarında). */
class Scan(val board: BoardRect, val info: Array<Array<SquareInfo>>, val highlighted: Array<BooleanArray>)

/** Fiqur şablonları (hər növ üçün bir neçə nümunə) və ağ/qara fiqur rəngini ayıran parlaqlıq həddi. */
class Templates(val samples: Map<Char, List<Sample>>, val thr: Float, val gap: Float) {

    fun serialize(): String {
        val sb = StringBuilder("v2;$thr;$gap")
        for ((ch, list) in samples) {
            for (s in list) {
                sb.append('\n').append(ch).append(' ').append(pack(s.edges)).append(' ').append(pack(s.mask))
            }
        }
        return sb.toString()
    }

    companion object {
        private fun pack(v: FloatArray): String {
            var mx = 0f
            for (x in v) if (x > mx) mx = x
            val bytes = ByteArray(v.size)
            if (mx > 0f) {
                for (i in v.indices) bytes[i] = (v[i] / mx * 255f).roundToInt().toByte()
            }
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        }

        private fun unpack(s: String): FloatArray? {
            val bytes = Base64.decode(s, Base64.NO_WRAP)
            if (bytes.size != FLEN) return null
            return normalize(FloatArray(FLEN) { (bytes[it].toInt() and 0xFF) / 255f })
        }

        fun parse(s: String): Templates? {
            try {
                val lines = s.split('\n')
                val head = lines[0].split(';')
                if (head.size != 3 || head[0] != "v2") return null
                val m = HashMap<Char, ArrayList<Sample>>()
                for (i in 1 until lines.size) {
                    val parts = lines[i].split(' ')
                    if (parts.size != 3 || parts[0].length != 1) return null
                    val e = unpack(parts[1]) ?: return null
                    val k = unpack(parts[2]) ?: return null
                    m.getOrPut(parts[0][0]) { ArrayList() }.add(Sample(e, k))
                }
                if (PIECES.any { m[it].isNullOrEmpty() }) return null
                return Templates(m, head[1].toFloat(), head[2].toFloat())
            } catch (e: Exception) {
                return null
            }
        }
    }
}

class CalibResult(val templates: Templates, val whiteBottom: Boolean)

class Reading(
    val placement: String,
    val whiteBottom: Boolean,
    /** 'w' / 'b'; son gediş işıqlandırılması tapılmayıbsa null. */
    val sideToMove: Char?,
    val enPassant: String,
    /** Oxunmuş fiqurlar ekran koordinatlarında [sətir][sütun], boş xana '.'. */
    val screen: Array<CharArray>,
    /** Şübhəli fiqur xanaları (sətir * 8 + sütun). */
    val weak: Set<Int>,
    /** Number of squares classified as occupied after position constraints. */
    val occupiedCount: Int
)

private fun normalize(v: FloatArray): FloatArray {
    var s = 0f
    for (x in v) s += x * x
    val n = sqrt(s)
    return if (n < 1e-6f) v.copyOf() else FloatArray(v.size) { v[it] / n }
}

/** [a] (w x h) massivini n x n şəbəkəyə sahə ortası ilə kiçildir. */
private fun resample(a: FloatArray, w: Int, h: Int, n: Int): FloatArray {
    val out = FloatArray(n * n)
    for (j in 0 until n) {
        val ya = j * h / n
        val yb = max((j + 1) * h / n, ya + 1)
        for (i in 0 until n) {
            val xa = i * w / n
            val xb = max((i + 1) * w / n, xa + 1)
            var s = 0f
            var c = 0
            for (y in ya until yb) {
                val o = y * w
                for (x in xa until xb) {
                    s += a[o + x]
                    c++
                }
            }
            out[j * n + i] = s / c
        }
    }
    return out
}

/**
 * Taxta tapma və fiqur tanıma (mövzudan və fiqur dəstindən asılı olmayaraq).
 *
 * - Taxta: ekrandakı iki dominant rəngin şahmat xanası düzülüşü ilə tapılır.
 * - Fiqurun siluet maskası: xananın fon rəngindən fərqli olan və xarici fondan ayrılan hər şey
 *   (konturun içi də daxil, buna görə ağ fiqur açıq xanada da düzgün tutulur).
 * - Fiqurun növü: siluet və kənar xəritəsinin şablonlarla oxşarlığı; ağ/qara rəng parlaqlıqdan.
 * - Şablonlar oyunun əvvəlindən (ilk 1-2 gediş) öyrənilir.
 */
object BoardReader {

    // ---------------------------------------------------------------- taxta tapma

    fun findBoard(b: Bitmap): BoardRect? {
        val w = b.width
        val h = b.height
        val step = 2
        val sw = w / step
        val sh = h / step
        if (sw < 32 || sh < 32) return null

        val sample = IntArray(sw * sh)
        val line = IntArray(w)
        for (sy in 0 until sh) {
            b.getPixels(line, 0, w, 0, sy * step, w, 1)
            for (sx in 0 until sw) sample[sy * sw + sx] = line[sx * step]
        }

        // rəng histoqramı: kanal başına 32 səviyyə, qonşu qutularla hamarlanmış
        val bins = IntArray(32768)
        for (c in sample) {
            bins[((Color.red(c) shr 3) shl 10) or ((Color.green(c) shr 3) shl 5) or (Color.blue(c) shr 3)]++
        }
        val smooth = IntArray(32768)
        for (r in 0 until 32) for (g in 0 until 32) for (bl in 0 until 32) {
            val cnt = bins[(r shl 10) or (g shl 5) or bl]
            if (cnt == 0) continue
            for (dr in -1..1) for (dg in -1..1) for (db in -1..1) {
                val rr = r + dr
                val gg = g + dg
                val bb = bl + db
                if (rr in 0..31 && gg in 0..31 && bb in 0..31) {
                    smooth[(rr shl 10) or (gg shl 5) or bb] += cnt
                }
            }
        }

        // ən çox yayılmış 8 rəng
        val minCount = sample.size / 60
        val peaks = ArrayList<IntArray>()
        for (iter in 0 until 8) {
            var best = -1
            var bestV = 0
            for (i in smooth.indices) {
                if (smooth[i] > bestV) {
                    bestV = smooth[i]
                    best = i
                }
            }
            if (best < 0 || bestV < minCount) break
            val pr = best shr 10
            val pg = (best shr 5) and 31
            val pb = best and 31
            for (r in max(0, pr - 3)..min(31, pr + 3)) for (g in max(0, pg - 3)..min(31, pg + 3)) {
                for (bl in max(0, pb - 3)..min(31, pb + 3)) smooth[(r shl 10) or (g shl 5) or bl] = 0
            }
            val cr = pr * 8 + 4
            val cg = pg * 8 + 4
            val cb = pb * 8 + 4
            var sr = 0L
            var sg = 0L
            var sb = 0L
            var n = 0L
            for (c in sample) {
                val r = Color.red(c)
                val g = Color.green(c)
                val bl = Color.blue(c)
                if (abs(r - cr) <= 14 && abs(g - cg) <= 14 && abs(bl - cb) <= 14) {
                    sr += r
                    sg += g
                    sb += bl
                    n++
                }
            }
            if (n > 0) peaks.add(intArrayOf((sr / n).toInt(), (sg / n).toInt(), (sb / n).toInt()))
        }
        if (peaks.size < 2) return null

        val ids = ByteArray(sample.size)
        for (i in sample.indices) {
            val c = sample[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val bl = Color.blue(c)
            var id = -1
            for (k in peaks.indices) {
                val p = peaks[k]
                if (abs(r - p[0]) <= 14 && abs(g - p[1]) <= 14 && abs(bl - p[2]) <= 14) {
                    id = k
                    break
                }
            }
            ids[i] = id.toByte()
        }

        var best: BoardRect? = null
        var bestScore = 0.75f
        for (a in peaks.indices) for (c in a + 1 until peaks.size) {
            val rows = IntArray(sh)
            val cols = IntArray(sw)
            var maxRow = 0
            var maxCol = 0
            for (sy in 0 until sh) {
                val o = sy * sw
                for (sx in 0 until sw) {
                    val id = ids[o + sx].toInt()
                    if (id == a || id == c) {
                        rows[sy]++
                        cols[sx]++
                    }
                }
            }
            for (v in rows) if (v > maxRow) maxRow = v
            for (v in cols) if (v > maxCol) maxCol = v
            val rt = max((maxRow * 0.15f).toInt(), 6)
            val ct = max((maxCol * 0.15f).toInt(), 6)
            val runY = longestRun(BooleanArray(sh) { rows[it] >= rt }) ?: continue
            val runX = longestRun(BooleanArray(sw) { cols[it] >= ct }) ?: continue
            val bw = (runX[1] - runX[0] + 1) * step
            val bh = (runY[1] - runY[0] + 1) * step
            if (bw < w / 3) continue
            for (cand in candidates(runX[0], runX[1], runY[0], runY[1], bw, bh, step, w, h)) {
                val score = checkerScore(ids, sw, sh, step, cand[0], cand[1], cand[2], a, c)
                if (score > bestScore) {
                    bestScore = score
                    best = BoardRect(cand[0], cand[1], cand[2])
                }
            }
        }
        return best
    }

    /** Ən uzun fasiləsiz True zolağı; 2-yə qədər boşluq zolağı kəsmir. [başlanğıc, son] qaytarır. */
    private fun longestRun(flags: BooleanArray, maxGap: Int = 2): IntArray? {
        var best: IntArray? = null
        var start = -1
        var last = -1
        for (i in flags.indices) {
            if (!flags[i]) continue
            if (start < 0) {
                start = i
            } else if (i - last > maxGap + 1) {
                val bb = best
                if (bb == null || last - start > bb[1] - bb[0]) best = intArrayOf(start, last)
                start = i
            }
            last = i
        }
        val bb = best
        if (start >= 0 && (bb == null || last - start > bb[1] - bb[0])) best = intArrayOf(start, last)
        return best
    }

    /** Rəng qutusu daxilində kvadrat namizədlər; qutu kvadrat deyilsə kvadratı sürüşdürür. */
    private fun candidates(
        minX: Int, maxX: Int, minY: Int, maxY: Int,
        bw: Int, bh: Int, step: Int, w: Int, h: Int
    ): List<IntArray> {
        val out = ArrayList<IntArray>()
        if (abs(bw - bh) <= bw * 0.08f) {
            val size = min((bw + bh) / 2, min(w - minX * step, h - minY * step))
            out.add(intArrayOf(minX * step, minY * step, size))
            return out
        }
        val sizes = if (bw == bh) listOf(bw) else listOf(max(bw, bh), min(bw, bh))
        for (size in sizes) {
            if (size < w / 3) continue
            val xa = minX * step
            val xb = max(xa, (maxX + 1) * step - size)
            val ya = minY * step
            val yb = max(ya, (maxY + 1) * step - size)
            val sx = max(2, (xb - xa) / 80)
            val sy = max(2, (yb - ya) / 80)
            var x = xa
            while (x <= xb) {
                var y = ya
                while (y <= yb) {
                    if (x + size <= w && y + size <= h) out.add(intArrayOf(x, y, size))
                    y += sy
                }
                x += sx
            }
        }
        return out
    }

    /** İki rəngin 8x8 şahmat düzülüşünə nə qədər uyğun olduğu (0..1). */
    private fun checkerScore(
        ids: ByteArray, sw: Int, sh: Int, step: Int,
        x: Int, y: Int, size: Int, a: Int, b: Int
    ): Float {
        val sq = size / 8f
        var m1 = 0
        var m2 = 0
        var n = 0
        for (r in 0 until 8) for (c in 0 until 8) {
            val even = (r + c) % 2 == 0
            val x0 = x + c * sq
            val y0 = y + r * sq
            // yuxarı-sağ və aşağı-sol künc: fiqur və koordinat yazılarından uzaq
            for (k in 0..1) {
                val px = if (k == 0) x0 + 0.88f * sq else x0 + 0.12f * sq
                val py = if (k == 0) y0 + 0.12f * sq else y0 + 0.88f * sq
                val sx = (px / step).toInt()
                val sy = (py / step).toInt()
                val id = if (sx in 0 until sw && sy in 0 until sh) ids[sy * sw + sx].toInt() else -1
                n++
                if (id == (if (even) a else b)) m1++
                if (id == (if (even) b else a)) m2++
            }
        }
        return max(m1, m2).toFloat() / n
    }

    /** İki taxta çərçivəsi demək olar eynidirsə true. */
    fun sameBoard(a: BoardRect, b: BoardRect) =
        abs(a.x - b.x) <= 4 && abs(a.y - b.y) <= 4 && abs(a.size - b.size) <= 4

    // ---------------------------------------------------------------- xana təhlili

    private fun emptyInfo() =
        SquareInfo(FloatArray(FLEN), FloatArray(FLEN), 0f, false, 0.6f, intArrayOf(0, 0, 0))

    private fun colorDist(a: IntArray, b: IntArray) = abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2])

    private fun analyze(b: Bitmap, r: BoardRect, row: Int, col: Int): SquareInfo {
        val sq = r.size / 8f
        val xa = (r.x + col * sq).roundToInt()
        val ya = (r.y + row * sq).roundToInt()
        val xb = (r.x + (col + 1) * sq).roundToInt()
        val yb = (r.y + (row + 1) * sq).roundToInt()
        val w = min(xb, b.width) - xa
        val h = min(yb, b.height) - ya
        if (xa < 0 || ya < 0 || w < 24 || h < 24) return emptyInfo()

        val px = IntArray(w * h)
        b.getPixels(px, 0, w, xa, ya, w, h)

        // fon rəngi: 4 küncün konsensusu (tutma halqası küncləri tutmur, yazılar ən çoxu 1-2 küncdədir)
        val p = max(3, (0.10f * w).toInt())
        val o = (0.03f * w).toInt()
        val corners = arrayOf(
            intArrayOf(o, o), intArrayOf(o, w - o - p),
            intArrayOf(h - o - p, o), intArrayOf(h - o - p, w - o - p)
        )
        val pc = Array(4) { IntArray(3) }
        val tmp = IntArray(p * p)
        for (k in 0 until 4) {
            for (ch in 0 until 3) {
                var n = 0
                for (yy in corners[k][0] until corners[k][0] + p) {
                    for (xx in corners[k][1] until corners[k][1] + p) {
                        val c = px[yy * w + xx]
                        tmp[n++] = when (ch) {
                            0 -> Color.red(c)
                            1 -> Color.green(c)
                            else -> Color.blue(c)
                        }
                    }
                }
                tmp.sort(0, n)
                pc[k][ch] = tmp[n / 2]
            }
        }
        var bestI = 0
        var bestN = -1
        for (i in 0 until 4) {
            var n = 0
            for (j in 0 until 4) if (colorDist(pc[i], pc[j]) < 30) n++
            if (n > bestN) {
                bestN = n
                bestI = i
            }
        }
        var br = 0
        var bg = 0
        var bb = 0
        var agree = 0
        for (j in 0 until 4) {
            if (colorDist(pc[bestI], pc[j]) < 30) {
                br += pc[j][0]
                bg += pc[j][1]
                bb += pc[j][2]
                agree++
            }
        }
        br /= agree
        bg /= agree
        bb /= agree

        // mərkəz kəsiyi: fondan fərqli piksellər "divar"
        val x0 = (0.10f * w).toInt()
        val x1 = (0.90f * w).toInt()
        val y0 = (0.10f * h).toInt()
        val y1 = (0.90f * h).toInt()
        val iw = x1 - x0
        val ih = y1 - y0
        val wall = BooleanArray(iw * ih)
        val lum = FloatArray(iw * ih)
        for (y in 0 until ih) {
            for (x in 0 until iw) {
                val c = px[(y0 + y) * w + x0 + x]
                val rr = Color.red(c)
                val gg = Color.green(c)
                val b2 = Color.blue(c)
                val i = y * iw + x
                wall[i] = abs(rr - br) + abs(gg - bg) + abs(b2 - bb) > WALL_T
                lum[i] = 0.299f * rr + 0.587f * gg + 0.114f * b2
            }
        }

        // kənardan başlayan daşqın: xarici fon; qalan hər şey (konturun içi də) fiqur siluetidir
        val ext = BooleanArray(iw * ih)
        val queue = IntArray(iw * ih)
        var qh = 0
        var qt = 0
        for (x in 0 until iw) {
            for (i in intArrayOf(x, (ih - 1) * iw + x)) {
                if (!wall[i] && !ext[i]) {
                    ext[i] = true
                    queue[qt++] = i
                }
            }
        }
        for (y in 0 until ih) {
            for (i in intArrayOf(y * iw, y * iw + iw - 1)) {
                if (!wall[i] && !ext[i]) {
                    ext[i] = true
                    queue[qt++] = i
                }
            }
        }
        while (qh < qt) {
            val i = queue[qh++]
            val x = i % iw
            val y = i / iw
            if (x > 0 && !wall[i - 1] && !ext[i - 1]) {
                ext[i - 1] = true
                queue[qt++] = i - 1
            }
            if (x < iw - 1 && !wall[i + 1] && !ext[i + 1]) {
                ext[i + 1] = true
                queue[qt++] = i + 1
            }
            if (y > 0 && !wall[i - iw] && !ext[i - iw]) {
                ext[i - iw] = true
                queue[qt++] = i - iw
            }
            if (y < ih - 1 && !wall[i + iw] && !ext[i + iw]) {
                ext[i + iw] = true
                queue[qt++] = i + iw
            }
        }

        var cnt = 0
        var minX = iw
        var maxX = -1
        var minY = ih
        var maxY = -1
        val hist = IntArray(256)
        for (y in 0 until ih) {
            for (x in 0 until iw) {
                val i = y * iw + x
                if (!ext[i]) {
                    cnt++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    hist[lum[i].toInt().coerceIn(0, 255)]++
                }
            }
        }
        val cover = cnt / (iw * ih).toFloat()
        val bbH = if (maxY >= 0) (maxY - minY + 1) / ih.toFloat() else 0f
        val bbW = if (maxX >= 0) (maxX - minX + 1) / iw.toFloat() else 0f
        val occupied = cover >= MIN_COVER && bbH >= MIN_BBOX && bbW >= MIN_BBOX
        val bgArr = intArrayOf(br, bg, bb)
        if (!occupied) return SquareInfo(FloatArray(FLEN), FloatArray(FLEN), 0f, false, 0.6f, bgArr)

        var fill = 0f
        val half = (cnt + 1) / 2
        var acc = 0
        for (v in 0..255) {
            acc += hist[v]
            if (acc >= half) {
                fill = v.toFloat()
                break
            }
        }

        val maskF = FloatArray(iw * ih) { if (ext[it]) 0f else 1f }
        val maskV = normalize(resample(maskF, iw, ih, F))

        val cell = resample(lum, iw, ih, F + 2)
        val n2 = F + 2
        val e = FloatArray(FLEN)
        for (j in 0 until F) {
            for (i in 0 until F) {
                val gx = cell[(j + 1) * n2 + i + 2] - cell[(j + 1) * n2 + i]
                val gy = cell[(j + 2) * n2 + i + 1] - cell[j * n2 + i + 1]
                e[j * F + i] = abs(gx) + abs(gy)
            }
        }
        return SquareInfo(normalize(e), maskV, fill, true, if (cover > 0.70f) 0.15f else 0.6f, bgArr)
    }

    private fun median(v: List<Int>): Float {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2].toFloat() else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }

    fun scan(b: Bitmap, r: BoardRect): Scan {
        val info = Array(8) { row -> Array(8) { col -> analyze(b, r, row, col) } }
        // son gediş işıqlandırması: xananın fonu öz rəng qrupunun (açıq/tünd) medianından fərqlidir
        val hl = Array(8) { BooleanArray(8) }
        for (par in 0..1) {
            val sq = ArrayList<IntArray>()
            for (row in 0 until 8) for (col in 0 until 8) if ((row + col) % 2 == par) sq.add(intArrayOf(row, col))
            val med = FloatArray(3) { ch -> median(sq.map { info[it[0]][it[1]].bg[ch] }) }
            for (s in sq) {
                val bg = info[s[0]][s[1]].bg
                val d = abs(bg[0] - med[0]) + abs(bg[1] - med[1]) + abs(bg[2] - med[2])
                if (d > 36f) hl[s[0]][s[1]] = true
            }
        }
        return Scan(r, info, hl)
    }

    // ---------------------------------------------------------------- kalibrə

    /**
     * Oyunun əvvəlində (hər tərəfdən ən çoxu bir gediş) şablonları çıxarır və
     * ağın aşağıda/yuxarıda olduğunu təyin edir. Başlanğıca yaxın deyilsə null.
     */
    fun calibrate(s: Scan): CalibResult? {
        val info = s.info
        var emptyHome = 0
        var midOcc = 0
        for (row in 0 until 8) for (col in 0 until 8) {
            val home = row <= 1 || row >= 6
            val occ = info[row][col].occupied
            if (home && !occ) emptyHome++
            if (!home && occ) midOcc++
        }
        if (emptyHome > 2 || midOcc > 2 || emptyHome != midOcc) return null

        fun meanFill(rows: IntRange): Float? {
            var sum = 0f
            var n = 0
            for (row in rows) for (col in 0 until 8) {
                if (info[row][col].occupied) {
                    sum += info[row][col].fill
                    n++
                }
            }
            return if (n == 0) null else sum / n
        }

        val top = meanFill(0..1) ?: return null
        val bottom = meanFill(6..7) ?: return null
        if (abs(top - bottom) < 40f) return null
        val whiteBottom = bottom > top

        val screenLayout = initialLayoutForScreen(whiteBottom)
        val layout = mapOf(
            0 to screenLayout[0],
            1 to screenLayout[1],
            6 to screenLayout[6],
            7 to screenLayout[7]
        )

        val map = HashMap<Char, ArrayList<Sample>>()
        var wl = 0f
        var wn = 0
        var bl = 0f
        var bn = 0
        for ((row, line) in layout) {
            for (col in 0 until 8) {
                val inf = info[row][col]
                if (!inf.occupied) continue // gediş edilmiş fiqurun başlanğıc xanası
                val ch = line[col]
                map.getOrPut(ch) { ArrayList() }.add(Sample(inf.edges, inf.mask))
                if (ch.isUpperCase()) {
                    wl += inf.fill
                    wn++
                } else {
                    bl += inf.fill
                    bn++
                }
            }
        }
        if (wn == 0 || bn == 0 || PIECES.any { map[it].isNullOrEmpty() }) return null
        val wm = wl / wn
        val bm = bl / bn
        return CalibResult(Templates(map, (wm + bm) / 2f, wm - bm), whiteBottom)
    }

    // ---------------------------------------------------------------- oxuma

    private fun sim(q: SquareInfo, s: Sample): Float {
        var m = 0f
        var e = 0f
        for (k in 0 until FLEN) {
            m += q.mask[k] * s.mask[k]
            e += q.edges[k] * s.edges[k]
        }
        return q.maskWeight * m + (1f - q.maskWeight) * e
    }

    /** 12 növün hər biri üçün bal (rəng uyğun gəlmirsə -1; rəng qeyri-müəyyəndirsə cərimə ilə). */
    private fun classScores(q: SquareInfo, t: Templates): FloatArray {
        val amb = abs(q.fill - t.thr) < 0.15f * t.gap
        val white = q.fill > t.thr
        val out = FloatArray(PIECES.length) { -1f }
        for (i in PIECES.indices) {
            val p = PIECES[i]
            val sideOk = p.isUpperCase() == white
            if (!sideOk && !amb) continue
            var best = -1f
            for (s in t.samples[p] ?: emptyList()) {
                val v = sim(q, s)
                if (v > best) best = v
            }
            out[i] = if (sideOk) best else best - 0.08f
        }
        return out
    }

    private fun argmax(v: FloatArray): Int {
        var bi = 0
        for (i in v.indices) if (v[i] > v[bi]) bi = i
        return bi
    }

    /**
     * Taxtanı oxuyur: fiqur yerləşməsi, istiqamət (avtomatik), növbə və en passant
     * (son gedişin işıqlandırmasından). Qeyri-mümkün vəziyyətlər (ikinci şah, son sırada piyada,
     * 8-dən çox piyada) ikinci ən yaxşı variantla düzəldilir. Mümkün deyilsə null.
     */
    fun read(
        s: Scan,
        t: Templates,
        prevWhiteBottom: Boolean,
        detectOrientation: Boolean = false
    ): Reading? {
        val info = s.info
        val scores = Array(8) { arrayOfNulls<FloatArray>(8) }
        val pick = Array(8) { CharArray(8) { '.' } }
        for (row in 0 until 8) for (col in 0 until 8) {
            if (!info[row][col].occupied) continue
            val sc = classScores(info[row][col], t)
            scores[row][col] = sc
            pick[row][col] = PIECES[argmax(sc)]
        }

        // istiqamət: ağ fiqurların orta mövqeyi qara fiqurlardan aşağıdadırsa ağ aşağıdadır
        var wRows = 0f
        var wN = 0
        var bRows = 0f
        var bN = 0
        for (row in 0 until 8) for (col in 0 until 8) {
            val ch = pick[row][col]
            if (ch.isUpperCase()) {
                wRows += row
                wN++
            } else if (ch.isLowerCase()) {
                bRows += row
                bN++
            }
        }
        if (wN == 0 || bN == 0) return null
        val delta = wRows / wN - bRows / bN
        // Orientation is calibrated once and then locked. Re-inferring it from moving pieces
        // every frame can flip the board after pawns advance or material is captured.
        val inferredWhiteBottom = when {
            delta > 0.6f -> true
            delta < -0.6f -> false
            else -> prevWhiteBottom
        }
        val whiteBottom = if (detectOrientation) inferredWhiteBottom else prevWhiteBottom
        fun rankOf(row: Int) = if (whiteBottom) 7 - row else row

        fun reassign(row: Int, col: Int, banned: String) {
            val v = scores[row][col]!!.copyOf()
            for (i in PIECES.indices) if (banned.indexOf(PIECES[i]) >= 0) v[i] = -9f
            pick[row][col] = PIECES[argmax(v)]
        }

        for (round in 0 until 3) {
            for (row in 0 until 8) for (col in 0 until 8) {
                if ((pick[row][col] == 'P' || pick[row][col] == 'p') && (rankOf(row) == 0 || rankOf(row) == 7)) {
                    reassign(row, col, "Pp")
                }
            }
            for (k in "Kk") {
                val idx = PIECES.indexOf(k)
                val pos = ArrayList<IntArray>()
                for (row in 0 until 8) for (col in 0 until 8) if (pick[row][col] == k) pos.add(intArrayOf(row, col))
                if (pos.size > 1) {
                    val keep = pos.maxByOrNull { scores[it[0]][it[1]]!![idx] }!!
                    for (q in pos) if (q !== keep) reassign(q[0], q[1], k.toString())
                } else if (pos.isEmpty()) {
                    val side = ArrayList<IntArray>()
                    for (row in 0 until 8) for (col in 0 until 8) {
                        val ch = pick[row][col]
                        if (ch != '.' && ch.isUpperCase() == k.isUpperCase()) side.add(intArrayOf(row, col))
                    }
                    val q = side.maxByOrNull { scores[it[0]][it[1]]!![idx] }
                    if (q != null) pick[q[0]][q[1]] = k
                }
            }
            for (pc in "Pp") {
                val idx = PIECES.indexOf(pc)
                val pos = ArrayList<IntArray>()
                for (row in 0 until 8) for (col in 0 until 8) if (pick[row][col] == pc) pos.add(intArrayOf(row, col))
                if (pos.size > 8) {
                    pos.sortByDescending { scores[it[0]][it[1]]!![idx] }
                    for (i in 8 until pos.size) reassign(pos[i][0], pos[i][1], pc.toString())
                }
            }
        }

        val grid = Array(8) { CharArray(8) { '.' } } // [rank-1][file]
        val marked = ArrayList<IntArray>()           // işıqlandırılmış xanalar [rank-1, file]
        val weak = HashSet<Int>()
        for (row in 0 until 8) for (col in 0 until 8) {
            val rank = rankOf(row)
            val file = if (whiteBottom) col else 7 - col
            grid[rank][file] = pick[row][col]
            if (s.highlighted[row][col]) marked.add(intArrayOf(rank, file))
            val sc = scores[row][col]
            if (sc != null && pick[row][col] != '.' && sc[PIECES.indexOf(pick[row][col])] < WEAK_SCORE) {
                weak.add(row * 8 + col)
            }
        }

        var wk = 0
        var bk = 0
        var wp = 0
        var bp = 0
        var wAll = 0
        var bAll = 0
        for (rank in 0 until 8) for (file in 0 until 8) {
            val ch = grid[rank][file]
            if (ch == 'K') wk++
            if (ch == 'k') bk++
            if (ch == 'P') wp++
            if (ch == 'p') bp++
            if (ch.isUpperCase()) wAll++
            if (ch.isLowerCase()) bAll++
            if ((ch == 'P' || ch == 'p') && (rank == 0 || rank == 7)) return null
        }
        if (wk != 1 || bk != 1 || wp > 8 || bp > 8 || wAll > 16 || bAll > 16) return null

        // son gediş: tam iki işıqlandırılmış xana, biri boş (haradan), biri dolu (haraya)
        var side: Char? = null
        var ep = "-"
        if (marked.size == 2) {
            val a = marked[0]
            val b2 = marked[1]
            val ca = grid[a[0]][a[1]]
            val cb = grid[b2[0]][b2[1]]
            var dest: IntArray? = null
            var from: IntArray? = null
            if (ca != '.' && cb == '.') {
                dest = a
                from = b2
            } else if (cb != '.' && ca == '.') {
                dest = b2
                from = a
            }
            if (dest != null && from != null) {
                val mover = grid[dest[0]][dest[1]]
                side = if (mover.isUpperCase()) 'b' else 'w'
                if ((mover == 'P' || mover == 'p') && dest[1] == from[1] && abs(dest[0] - from[0]) == 2) {
                    val mid = (dest[0] + from[0]) / 2
                    ep = "${'a' + dest[1]}${mid + 1}"
                }
            }
        }

        val sb = StringBuilder()
        for (rank in 7 downTo 0) {
            var empty = 0
            for (file in 0 until 8) {
                val ch = grid[rank][file]
                if (ch == '.') {
                    empty++
                } else {
                    if (empty > 0) {
                        sb.append(empty)
                        empty = 0
                    }
                    sb.append(ch)
                }
            }
            if (empty > 0) sb.append(empty)
            if (rank > 0) sb.append('/')
        }
        val placement = sb.toString()
        // A clean initial position has a known side to move even before any last-move
        // highlight exists. Never guess the side for non-initial positions.
        if (side == null) side = defaultSideToMove(placement)
        return Reading(placement, whiteBottom, side, ep, pick, weak, wAll + bAll)
    }

    // ---------------------------------------------------------------- köməkçilər

    private const val START_PLACEMENT =
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"

    fun defaultSideToMove(placement: String): Char? =
        if (placement == START_PLACEMENT) 'w' else null

    /**
     * Expected piece symbols by screenshot row, from screen-left to screen-right.
     * A black-bottom board is rotated 180 degrees, so each back rank is reversed.
     */
    fun initialLayoutForScreen(whiteBottom: Boolean): List<String> =
        if (whiteBottom) {
            listOf("rnbqkbnr", "pppppppp", "........", "........",
                "........", "........", "PPPPPPPP", "RNBQKBNR")
        } else {
            listOf("RNBKQBNR", "PPPPPPPP", "........", "........",
                "........", "........", "pppppppp", "rnbkqbnr")
        }

    fun isInitialPosition(placement: String): Boolean = placement == START_PLACEMENT

    /**
     * Castling rights are monotonic: once the original king/rook leaves its home square,
     * the corresponding right is permanently removed, even if a piece later returns there.
     */
    fun updateCastling(old: String, previous: String, current: String): String {
        if (old == "-") return "-"
        val keep = StringBuilder()
        for (right in old) {
            val kingSquare = if (right.isUpperCase()) "e1" else "e8"
            val rookSquare = when (right) {
                'K' -> "h1"
                'Q' -> "a1"
                'k' -> "h8"
                'q' -> "a8"
                else -> continue
            }
            val king = if (right.isUpperCase()) 'K' else 'k'
            val rook = if (right.isUpperCase()) 'R' else 'r'
            val kingStayed = pieceAt(previous, kingSquare) == king &&
                pieceAt(current, kingSquare) == king
            val rookStayed = pieceAt(previous, rookSquare) == rook &&
                pieceAt(current, rookSquare) == rook
            if (kingStayed && rookStayed) keep.append(right)
        }
        return if (keep.isEmpty()) "-" else keep.toString()
    }

    /** Compatibility helper; current position alone cannot restore historical castling rights. */
    fun updateCastling(placement: String, old: String): String {
        if (old == "-") return "-"
        val keep = StringBuilder()
        for (right in old) {
            val kingSquare = if (right.isUpperCase()) "e1" else "e8"
            val rookSquare = when (right) {
                'K' -> "h1"; 'Q' -> "a1"; 'k' -> "h8"; 'q' -> "a8"; else -> continue
            }
            val king = if (right.isUpperCase()) 'K' else 'k'
            val rook = if (right.isUpperCase()) 'R' else 'r'
            if (pieceAt(placement, kingSquare) == king && pieceAt(placement, rookSquare) == rook) {
                keep.append(right)
            }
        }
        return if (keep.isEmpty()) "-" else keep.toString()
    }

    /** FEN-də verilən xanadakı fiqur (məsələn "e2" -> 'P'), yoxdursa null. */
    fun pieceAt(fen: String, square: String): Char? {
        val ranks = fen.substringBefore(' ').split('/')
        val rank = square[1] - '1'
        val line = ranks.getOrNull(7 - rank) ?: return null
        val target = square[0] - 'a'
        var file = 0
        for (c in line) {
            if (c.isDigit()) {
                file += c - '0'
            } else {
                if (file == target) return c
                file++
            }
        }
        return null
    }

    /** Xananın ekrandakı mərkəzi, məsələn "e2". */
    fun center(r: BoardRect, square: String, whiteBottom: Boolean): Pair<Float, Float> {
        val file = square[0] - 'a'
        val rank = square[1] - '1'
        val col = if (whiteBottom) file else 7 - file
        val row = if (whiteBottom) 7 - rank else rank
        val sq = r.size / 8f
        return Pair(r.x + (col + 0.5f) * sq, r.y + (row + 0.5f) * sq)
    }
}
