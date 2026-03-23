package com.codeeditor.app.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * Native Canvas-based terminal renderer. Draws the TerminalEmulator screen buffer
 * directly - no WebView, no TextView. Fast like JuiceSSH/Termux.
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var emulator: TerminalEmulator? = null
    var onTerminalSizeChanged: ((rows: Int, cols: Int) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 13f * resources.displayMetrics.scaledDensity
    }

    private var cellWidth = 0f
    private var cellHeight = 0f
    private val bgPaint = Paint()

    companion object {
        // Standard 16 ANSI colors
        private val COLORS = intArrayOf(
            0xFF000000.toInt(), 0xFFCD0000.toInt(), 0xFF00CD00.toInt(), 0xFFCDCD00.toInt(),
            0xFF0000EE.toInt(), 0xFFCD00CD.toInt(), 0xFF00CDCD.toInt(), 0xFFE5E5E5.toInt(),
            0xFF7F7F7F.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFFFFFF00.toInt(),
            0xFF5C5CFF.toInt(), 0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFFFFFFFF.toInt(),
        )
        private const val DEFAULT_BG = 0xFF121212.toInt()
        private const val CURSOR_COLOR = 0xFFCCCCCC.toInt()
    }

    init {
        updateMetrics()
    }

    private fun updateMetrics() {
        val fm = paint.fontMetrics
        cellHeight = fm.bottom - fm.top
        cellWidth = paint.measureText("M")
    }

    fun computeSize(): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 24 to 80
        return (height / cellHeight).toInt().coerceAtLeast(1) to
                (width / cellWidth).toInt().coerceAtLeast(1)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMetrics()
        val (rows, cols) = computeSize()
        emulator?.resize(rows, cols)
        onTerminalSizeChanged?.invoke(rows, cols)
    }

    override fun onDraw(canvas: Canvas) {
        val em = emulator ?: run { canvas.drawColor(DEFAULT_BG); return }

        canvas.drawColor(DEFAULT_BG)
        val topOffset = -paint.fontMetrics.top

        synchronized(em) {
            for (row in 0 until em.rows) {
                val y = row * cellHeight + topOffset
                for (col in 0 until em.cols) {
                    val cell = em.getCell(row, col)
                    val x = col * cellWidth
                    val isCursor = row == em.cursorRow && col == em.cursorCol

                    val cellFg = if (cell.inverse) colorOf(cell.bg, true) else colorOf(cell.fg, false)
                    val cellBg = if (cell.inverse) colorOf(cell.fg, false) else colorBg(cell.bg)

                    // Draw cell background
                    if (cellBg != DEFAULT_BG || isCursor) {
                        bgPaint.color = if (isCursor) CURSOR_COLOR else cellBg
                        canvas.drawRect(x, row * cellHeight, x + cellWidth, (row + 1) * cellHeight, bgPaint)
                    }

                    // Draw character
                    val ch = cell.char
                    if (ch != ' ' && ch != '\u0000') {
                        paint.color = if (isCursor) DEFAULT_BG else cellFg
                        paint.isFakeBoldText = cell.bold
                        paint.isUnderlineText = cell.underline
                        canvas.drawText(ch.toString(), x, y, paint)
                        paint.isFakeBoldText = false
                        paint.isUnderlineText = false
                    }
                }
            }
        }
    }

    private fun colorOf(index: Int, isBg: Boolean): Int = when {
        index < 16 -> COLORS[index]
        index < 232 -> {
            val i = index - 16
            val r = (i / 36) * 51; val g = ((i % 36) / 6) * 51; val b = (i % 6) * 51
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        index < 256 -> {
            val v = 8 + (index - 232) * 10
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        else -> if (isBg) DEFAULT_BG else COLORS[7]
    }

    private fun colorBg(index: Int): Int = if (index == 0) DEFAULT_BG else colorOf(index, true)
}
