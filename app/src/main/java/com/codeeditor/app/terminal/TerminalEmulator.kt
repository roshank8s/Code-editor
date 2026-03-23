package com.codeeditor.app.terminal

/**
 * VT100/ANSI terminal emulator - parses escape sequences and maintains screen buffer.
 * Similar to what JuiceSSH/Termux use internally.
 */
class TerminalEmulator(var rows: Int = 24, var cols: Int = 80) {

    data class Cell(
        var char: Char = ' ',
        var fg: Int = 7,
        var bg: Int = 0,
        var bold: Boolean = false,
        var underline: Boolean = false,
        var inverse: Boolean = false
    )

    private var screen: Array<Array<Cell>> = Array(rows) { Array(cols) { Cell() } }
    var cursorRow = 0; private set
    var cursorCol = 0; private set

    // Current text attributes
    private var fg = 7
    private var bg = 0
    private var bold = false
    private var underline = false
    private var inverse = false

    // Parser state machine
    private enum class State { NORMAL, ESC, CSI, OSC }
    private var state = State.NORMAL
    private val csiParams = StringBuilder()

    // Scroll region
    private var scrollTop = 0
    private var scrollBottom = rows - 1

    // Saved cursor
    private var savedRow = 0
    private var savedCol = 0

    // UTF-8 decoder
    private val utf8Buf = ByteArray(4)
    private var utf8Idx = 0
    private var utf8Len = 0

    fun getCell(row: Int, col: Int): Cell {
        if (row in 0 until rows && col in 0 until cols) return screen[row][col]
        return Cell()
    }

    // =========================================================================
    // Byte processing (entry point from SSH stream)
    // =========================================================================

    @Synchronized
    fun processBytes(data: ByteArray, offset: Int, length: Int) {
        for (i in offset until offset + length) {
            val b = data[i].toInt() and 0xFF
            if (utf8Len > 0) {
                utf8Buf[utf8Idx++] = b.toByte()
                if (utf8Idx == utf8Len) {
                    val str = String(utf8Buf, 0, utf8Len, Charsets.UTF_8)
                    for (ch in str) processChar(ch.code)
                    utf8Len = 0; utf8Idx = 0
                }
            } else if (b < 0x80) {
                processChar(b)
            } else if (b >= 0xC0 && b < 0xE0) {
                utf8Buf[0] = b.toByte(); utf8Idx = 1; utf8Len = 2
            } else if (b >= 0xE0 && b < 0xF0) {
                utf8Buf[0] = b.toByte(); utf8Idx = 1; utf8Len = 3
            } else if (b >= 0xF0) {
                utf8Buf[0] = b.toByte(); utf8Idx = 1; utf8Len = 4
            }
        }
    }

    private fun processChar(c: Int) {
        when (state) {
            State.NORMAL -> when (c) {
                0x07 -> {} // BEL
                0x08 -> { if (cursorCol > 0) cursorCol-- } // BS
                0x09 -> { cursorCol = ((cursorCol / 8) + 1) * 8; if (cursorCol >= cols) cursorCol = cols - 1 } // TAB
                0x0A, 0x0B, 0x0C -> lineFeed() // LF, VT, FF
                0x0D -> cursorCol = 0 // CR
                0x1B -> state = State.ESC // ESC
                else -> { if (c >= 0x20) putChar(c.toChar()) }
            }
            State.ESC -> {
                state = State.NORMAL
                when (c.toChar()) {
                    '[' -> { state = State.CSI; csiParams.clear() }
                    ']' -> { state = State.OSC }
                    '7' -> { savedRow = cursorRow; savedCol = cursorCol }
                    '8' -> { cursorRow = savedRow; cursorCol = savedCol }
                    'D' -> lineFeed()
                    'M' -> reverseLineFeed()
                    'c' -> reset()
                    '(' -> state = State.ESC // Skip charset designation
                    else -> {}
                }
            }
            State.CSI -> {
                val ch = c.toChar()
                if (ch in '0'..'9' || ch == ';' || ch == '?' || ch == '>') {
                    csiParams.append(ch)
                } else {
                    state = State.NORMAL
                    handleCSI(ch)
                }
            }
            State.OSC -> {
                if (c == 0x07 || c == 0x1B) state = State.NORMAL // BEL or ESC terminates
            }
        }
    }

    // =========================================================================
    // CSI sequence handling
    // =========================================================================

    private fun param(index: Int, default: Int = 0): Int {
        if (csiParams.isEmpty()) return default
        val parts = csiParams.toString().removePrefix("?").split(';')
        val v = parts.getOrNull(index)?.toIntOrNull() ?: 0
        return if (v > 0) v else default
    }

    private fun handleCSI(ch: Char) {
        when (ch) {
            'A' -> cursorRow = (cursorRow - param(0, 1)).coerceAtLeast(0)
            'B' -> cursorRow = (cursorRow + param(0, 1)).coerceAtMost(rows - 1)
            'C' -> cursorCol = (cursorCol + param(0, 1)).coerceAtMost(cols - 1)
            'D' -> cursorCol = (cursorCol - param(0, 1)).coerceAtLeast(0)
            'E' -> { cursorCol = 0; cursorRow = (cursorRow + param(0, 1)).coerceAtMost(rows - 1) }
            'F' -> { cursorCol = 0; cursorRow = (cursorRow - param(0, 1)).coerceAtLeast(0) }
            'G' -> cursorCol = (param(0, 1) - 1).coerceIn(0, cols - 1)
            'H', 'f' -> {
                cursorRow = (param(0, 1) - 1).coerceIn(0, rows - 1)
                cursorCol = (param(1, 1) - 1).coerceIn(0, cols - 1)
            }
            'J' -> eraseDisplay(param(0, 0))
            'K' -> eraseLine(param(0, 0))
            'L' -> insertLines(param(0, 1))
            'M' -> deleteLines(param(0, 1))
            'P' -> deleteChars(param(0, 1))
            '@' -> insertChars(param(0, 1))
            'S' -> scrollUp(param(0, 1))
            'T' -> scrollDown(param(0, 1))
            'd' -> cursorRow = (param(0, 1) - 1).coerceIn(0, rows - 1)
            'm' -> handleSGR()
            'r' -> {
                scrollTop = (param(0, 1) - 1).coerceIn(0, rows - 1)
                scrollBottom = (param(1, rows) - 1).coerceIn(0, rows - 1)
                cursorRow = 0; cursorCol = 0
            }
            'h', 'l', 'c', 'n', 't' -> {} // Modes, device attrs, etc. - ignored
        }
    }

    // =========================================================================
    // SGR (colors and text attributes)
    // =========================================================================

    private fun handleSGR() {
        val raw = csiParams.toString().ifEmpty { "0" }
        val params = raw.split(';').map { it.toIntOrNull() ?: 0 }
        var i = 0
        while (i < params.size) {
            when (params[i]) {
                0 -> { fg = 7; bg = 0; bold = false; underline = false; inverse = false }
                1 -> bold = true
                4 -> underline = true
                7 -> inverse = true
                22 -> bold = false
                24 -> underline = false
                27 -> inverse = false
                in 30..37 -> fg = params[i] - 30
                38 -> { if (i + 2 < params.size && params[i + 1] == 5) { fg = params[i + 2]; i += 2 } }
                39 -> fg = 7
                in 40..47 -> bg = params[i] - 40
                48 -> { if (i + 2 < params.size && params[i + 1] == 5) { bg = params[i + 2]; i += 2 } }
                49 -> bg = 0
                in 90..97 -> fg = params[i] - 90 + 8
                in 100..107 -> bg = params[i] - 100 + 8
            }
            i++
        }
    }

    // =========================================================================
    // Screen operations
    // =========================================================================

    private fun putChar(ch: Char) {
        if (cursorCol >= cols) {
            cursorCol = 0
            lineFeed()
        }
        screen[cursorRow][cursorCol] = Cell(ch, fg, bg, bold, underline, inverse)
        cursorCol++
    }

    private fun lineFeed() {
        if (cursorRow == scrollBottom) {
            scrollUp(1)
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
    }

    private fun reverseLineFeed() {
        if (cursorRow == scrollTop) scrollDown(1)
        else if (cursorRow > 0) cursorRow--
    }

    private fun scrollUp(n: Int) {
        repeat(n) {
            for (r in scrollTop until scrollBottom) screen[r] = screen[r + 1]
            screen[scrollBottom] = Array(cols) { Cell() }
        }
    }

    private fun scrollDown(n: Int) {
        repeat(n) {
            for (r in scrollBottom downTo scrollTop + 1) screen[r] = screen[r - 1]
            screen[scrollTop] = Array(cols) { Cell() }
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseLine(0)
                for (r in cursorRow + 1 until rows) for (c in 0 until cols) screen[r][c] = Cell()
            }
            1 -> {
                for (r in 0 until cursorRow) for (c in 0 until cols) screen[r][c] = Cell()
                for (c in 0..cursorCol.coerceAtMost(cols - 1)) screen[cursorRow][c] = Cell()
            }
            2, 3 -> for (r in 0 until rows) for (c in 0 until cols) screen[r][c] = Cell()
        }
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            0 -> for (c in cursorCol until cols) screen[cursorRow][c] = Cell()
            1 -> for (c in 0..cursorCol.coerceAtMost(cols - 1)) screen[cursorRow][c] = Cell()
            2 -> for (c in 0 until cols) screen[cursorRow][c] = Cell()
        }
    }

    private fun insertLines(n: Int) {
        repeat(n) {
            for (r in scrollBottom downTo cursorRow + 1) screen[r] = screen[r - 1]
            screen[cursorRow] = Array(cols) { Cell() }
        }
    }

    private fun deleteLines(n: Int) {
        repeat(n) {
            for (r in cursorRow until scrollBottom) screen[r] = screen[r + 1]
            screen[scrollBottom] = Array(cols) { Cell() }
        }
    }

    private fun insertChars(n: Int) {
        repeat(n) {
            for (c in cols - 1 downTo cursorCol + 1) screen[cursorRow][c] = screen[cursorRow][c - 1]
            screen[cursorRow][cursorCol] = Cell()
        }
    }

    private fun deleteChars(n: Int) {
        repeat(n) {
            for (c in cursorCol until cols - 1) screen[cursorRow][c] = screen[cursorRow][c + 1]
            screen[cursorRow][cols - 1] = Cell()
        }
    }

    // =========================================================================
    // Resize / Reset
    // =========================================================================

    @Synchronized
    fun resize(newRows: Int, newCols: Int) {
        if (newRows == rows && newCols == cols) return
        val newScreen = Array(newRows) { r ->
            Array(newCols) { c ->
                if (r < rows && c < cols) screen[r][c] else Cell()
            }
        }
        rows = newRows
        cols = newCols
        screen = newScreen
        scrollTop = 0
        scrollBottom = rows - 1
        cursorRow = cursorRow.coerceAtMost(rows - 1)
        cursorCol = cursorCol.coerceAtMost(cols - 1)
    }

    fun reset() {
        fg = 7; bg = 0; bold = false; underline = false; inverse = false
        cursorRow = 0; cursorCol = 0; scrollTop = 0; scrollBottom = rows - 1
        for (r in 0 until rows) for (c in 0 until cols) screen[r][c] = Cell()
    }
}
