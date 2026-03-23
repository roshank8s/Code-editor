package com.codeeditor.app.editor

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.codeeditor.app.R
import com.codeeditor.app.utils.Constants

@SuppressLint("ClickableViewAccessibility")
class FloatingKeyboardView(
    context: Context,
    private val parentView: ViewGroup
) : FrameLayout(context) {

    private val expandedPanel: LinearLayout
    private val collapsedPill: View
    private val dragHandle: View
    private val opacitySlider: SeekBar
    private val keyboardContainer: LinearLayout
    private val prefs: SharedPreferences =
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    // Drag state
    private var isCollapsed = false
    private var isDragging = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var viewStartX = 0f
    private var viewStartY = 0f

    // Keyboard state
    private var shiftActive = false
    private var capsLock = false
    private var extrasVisible = false
    private var keyScale = 1.0f

    // Key view references for label updates
    private val letterKeyViews = mutableListOf<TextView>()
    private data class NumKeyRef(val view: TextView, val normal: String, val shifted: String)
    private val numKeyRefs = mutableListOf<NumKeyRef>()
    private var shiftKeyView: TextView? = null
    private var ctrlKeyView: TextView? = null
    private var altKeyView: TextView? = null
    private var extrasContainer: LinearLayout? = null

    // Pinch-to-resize
    private val scaleDetector: ScaleGestureDetector

    var onKeyPressed: ((KeyAction) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val baseWidthDp = 360f
    private val baseKeyHeightDp = 38f
    private val keyMarginDp = 1.5f

    init {
        LayoutInflater.from(context).inflate(R.layout.layout_floating_keyboard, this, true)

        expandedPanel = findViewById(R.id.expandedPanel)
        collapsedPill = findViewById(R.id.collapsedPill)
        dragHandle = findViewById(R.id.dragHandle)
        opacitySlider = findViewById(R.id.opacitySlider)
        keyboardContainer = findViewById(R.id.keyboardContainer)

        scaleDetector = ScaleGestureDetector(context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    keyScale = (keyScale * detector.scaleFactor).coerceIn(0.5f, 1.5f)
                    buildKeyboard()
                    saveState()
                    return true
                }
            })

        setupDragHandle()
        setupOpacitySlider()
        buildKeyboard()
        restoreState()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.pointerCount >= 2) {
            scaleDetector.onTouchEvent(ev)
            if (scaleDetector.isInProgress) return true
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount >= 2) {
            scaleDetector.onTouchEvent(event)
            if (scaleDetector.isInProgress) return true
        }
        return super.onTouchEvent(event)
    }

    private fun dp(value: Float): Int = (value * density * keyScale).toInt()
    private fun ts(baseSp: Float): Float = baseSp * keyScale

    // =========================================================================
    // Keyboard Building
    // =========================================================================

    private fun buildKeyboard() {
        keyboardContainer.removeAllViews()
        letterKeyViews.clear()
        numKeyRefs.clear()

        expandedPanel.layoutParams = (expandedPanel.layoutParams).apply {
            width = (baseWidthDp * density * keyScale).toInt()
        }

        val h = dp(baseKeyHeightDp)
        val m = dp(keyMarginDp)

        addModifierRow(h, m)

        val extras = buildExtrasSection(h, m)
        extrasContainer = extras
        extras.visibility = if (extrasVisible) VISIBLE else GONE
        keyboardContainer.addView(extras)

        addNumberRow(h, m)
        addLetterRow(listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"), h, m)
        addLetterRow(listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"), h, m, padWeight = 0.5f)
        addBottomLetterRow(h, m)
        addSpaceRow(h, m)
    }

    private fun key(
        text: String, h: Int, m: Int, w: Float = 1f,
        bg: Int = R.drawable.bg_key, sp: Float = 14f
    ): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ts(sp))
            gravity = Gravity.CENTER
            setBackgroundResource(bg)
            isClickable = true
            isFocusable = false
            isFocusableInTouchMode = false
            typeface = Typeface.DEFAULT
            layoutParams = LinearLayout.LayoutParams(0, h, w).apply {
                setMargins(m, m, m, m)
            }
        }
    }

    private fun row(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun addModifierRow(h: Int, m: Int) {
        val r = row()
        r.addView(key("ESC", h, m, sp = 10f).apply {
            setOnClickListener { onKeyPressed?.invoke(KeyAction.SpecialKey(KeyAction.Special.ESC)) }
        })
        r.addView(key("TAB", h, m, sp = 10f).apply {
            setOnClickListener { onKeyPressed?.invoke(KeyAction.SpecialKey(KeyAction.Special.TAB)) }
        })
        ctrlKeyView = key("CTRL", h, m, bg = R.drawable.bg_key_special, sp = 10f).apply {
            alpha = 0.6f
            setOnClickListener { onKeyPressed?.invoke(KeyAction.ModifierToggle(KeyAction.Modifier.CTRL)) }
        }
        r.addView(ctrlKeyView!!)
        altKeyView = key("ALT", h, m, bg = R.drawable.bg_key_special, sp = 10f).apply {
            alpha = 0.6f
            setOnClickListener { onKeyPressed?.invoke(KeyAction.ModifierToggle(KeyAction.Modifier.ALT)) }
        }
        r.addView(altKeyView!!)
        r.addView(key("···", h, m, bg = R.drawable.bg_key_special, sp = 12f).apply {
            setOnClickListener { toggleExtras() }
        })
        keyboardContainer.addView(r)
    }

    private fun buildExtrasSection(h: Int, m: Int): LinearLayout {
        val section = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Symbols row
        val symbols = listOf(
            "|", "/", "-", "~", "_", ":", ";", "{", "}", "[", "]",
            "'", "\"", "\\", "&", "#", "?", "<", ">", "=", "+", "*", "^", "`"
        )
        section.addView(scrollRow(symbols.map { sym ->
            key(sym, h, m, sp = 13f).apply {
                layoutParams = LinearLayout.LayoutParams(dp(30f), h).apply {
                    setMargins(m, m, m, m)
                }
                setOnClickListener { emitChar(sym) }
            }
        }))

        // Shortcuts + navigation row
        data class SC(val label: String, val action: KeyAction)
        val shortcuts = listOf(
            SC("SAVE", KeyAction.ShortcutKey(KeyAction.Shortcut.SAVE)),
            SC("UNDO", KeyAction.ShortcutKey(KeyAction.Shortcut.UNDO)),
            SC("REDO", KeyAction.ShortcutKey(KeyAction.Shortcut.REDO)),
            SC("CMD", KeyAction.ShortcutKey(KeyAction.Shortcut.COMMAND_PALETTE)),
            SC("FIND", KeyAction.ShortcutKey(KeyAction.Shortcut.FIND)),
            SC("CLOSE", KeyAction.ShortcutKey(KeyAction.Shortcut.CLOSE_TAB)),
            SC("HOME", KeyAction.NavigationKey(KeyAction.Navigation.HOME)),
            SC("END", KeyAction.NavigationKey(KeyAction.Navigation.END)),
            SC("PGUP", KeyAction.NavigationKey(KeyAction.Navigation.PAGE_UP)),
            SC("PGDN", KeyAction.NavigationKey(KeyAction.Navigation.PAGE_DOWN)),
            SC("DEL", KeyAction.NavigationKey(KeyAction.Navigation.DELETE)),
            SC("\u2190", KeyAction.NavigationKey(KeyAction.Navigation.LEFT)),
            SC("\u2192", KeyAction.NavigationKey(KeyAction.Navigation.RIGHT)),
            SC("\u2191", KeyAction.NavigationKey(KeyAction.Navigation.UP)),
            SC("\u2193", KeyAction.NavigationKey(KeyAction.Navigation.DOWN)),
        )
        section.addView(scrollRow(shortcuts.map { sc ->
            key(sc.label, h, m, sp = 10f).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, h
                ).apply { setMargins(m, m, m, m) }
                minWidth = dp(34f)
                setOnClickListener { onKeyPressed?.invoke(sc.action) }
            }
        }))

        return section
    }

    private fun scrollRow(views: List<View>): HorizontalScrollView {
        val sv = HorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isHorizontalScrollBarEnabled = false
        }
        val ll = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        for (v in views) ll.addView(v)
        sv.addView(ll)
        return sv
    }

    private fun emitChar(c: String) {
        onKeyPressed?.invoke(KeyAction.Character(c))
        if (shiftActive && !capsLock) {
            shiftActive = false
            updateKeyLabels()
        }
    }

    private fun addNumberRow(h: Int, m: Int) {
        val r = row()
        val normals = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        val shifted = listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")")
        normals.forEachIndexed { i, n ->
            val k = key(n, h, m, sp = 13f).apply {
                setOnClickListener { emitChar(if (shiftActive) shifted[i] else n) }
            }
            r.addView(k)
            numKeyRefs.add(NumKeyRef(k, n, shifted[i]))
        }
        keyboardContainer.addView(r)
    }

    private fun addLetterRow(letters: List<String>, h: Int, m: Int, padWeight: Float = 0f) {
        val r = row()
        if (padWeight > 0) {
            r.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, h, padWeight)
            })
        }
        for (l in letters) {
            val k = key(l, h, m).apply {
                setOnClickListener { emitChar(if (shiftActive) l.uppercase() else l) }
            }
            r.addView(k)
            letterKeyViews.add(k)
        }
        if (padWeight > 0) {
            r.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, h, padWeight)
            })
        }
        keyboardContainer.addView(r)
    }

    private fun addBottomLetterRow(h: Int, m: Int) {
        val r = row()
        val letters = listOf("z", "x", "c", "v", "b", "n", "m")

        shiftKeyView = key("\u21E7", h, m, w = 1.5f, bg = R.drawable.bg_key_special, sp = 16f).apply {
            alpha = 0.6f
            setOnClickListener {
                when {
                    capsLock -> {
                        capsLock = false
                        shiftActive = false
                    }
                    shiftActive -> {
                        capsLock = true
                    }
                    else -> {
                        shiftActive = true
                    }
                }
                updateKeyLabels()
            }
        }
        r.addView(shiftKeyView!!)

        for (l in letters) {
            val k = key(l, h, m).apply {
                setOnClickListener { emitChar(if (shiftActive) l.uppercase() else l) }
            }
            r.addView(k)
            letterKeyViews.add(k)
        }

        r.addView(key("\u232B", h, m, w = 1.5f, bg = R.drawable.bg_key_special, sp = 16f).apply {
            setOnClickListener { onKeyPressed?.invoke(KeyAction.Backspace) }
        })
        keyboardContainer.addView(r)
    }

    private fun addSpaceRow(h: Int, m: Int) {
        val r = row()
        r.addView(key(",", h, m).apply {
            setOnClickListener { emitChar(",") }
        })
        r.addView(key(".", h, m).apply {
            setOnClickListener { emitChar(".") }
        })
        r.addView(key("", h, m, w = 5f, bg = R.drawable.bg_key_special).apply {
            setOnClickListener { emitChar(" ") }
        })
        r.addView(key("\u21B5", h, m, w = 1.5f, bg = R.drawable.bg_key_special, sp = 16f).apply {
            setOnClickListener { onKeyPressed?.invoke(KeyAction.Enter) }
        })
        keyboardContainer.addView(r)
    }

    // =========================================================================
    // Extras toggle
    // =========================================================================

    private fun toggleExtras() {
        extrasVisible = !extrasVisible
        extrasContainer?.visibility = if (extrasVisible) VISIBLE else GONE
        post { clampToParent() }
    }

    // =========================================================================
    // Key label updates
    // =========================================================================

    private fun updateKeyLabels() {
        for (k in letterKeyViews) {
            k.text = if (shiftActive) k.text.toString().uppercase()
            else k.text.toString().lowercase()
        }
        for (ref in numKeyRefs) {
            ref.view.text = if (shiftActive) ref.shifted else ref.normal
        }
        shiftKeyView?.alpha = when {
            capsLock -> 1.0f
            shiftActive -> 0.85f
            else -> 0.6f
        }
    }

    fun updateModifierState(modifier: KeyAction.Modifier, active: Boolean) {
        when (modifier) {
            KeyAction.Modifier.CTRL -> ctrlKeyView?.alpha = if (active) 1.0f else 0.6f
            KeyAction.Modifier.ALT -> altKeyView?.alpha = if (active) 1.0f else 0.6f
            KeyAction.Modifier.SHIFT -> {
                shiftActive = active
                if (!active) capsLock = false
                updateKeyLabels()
            }
        }
    }

    // =========================================================================
    // Drag / Collapse
    // =========================================================================

    private fun setupDragHandle() {
        val gestureDetector = GestureDetector(context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    toggleCollapse()
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    toggleOpacitySlider()
                }
            })

        val makeDragListener = { _: View ->
            View.OnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dragStartX = event.rawX
                        dragStartY = event.rawY
                        viewStartX = this.x
                        viewStartY = this.y
                        isDragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - dragStartX
                        val dy = event.rawY - dragStartY
                        if (dx * dx + dy * dy > 25) {
                            isDragging = true
                            this.x = viewStartX + dx
                            this.y = viewStartY + dy
                            clampToParent()
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isDragging) {
                            snapToEdge()
                            saveState()
                        }
                        isDragging = false
                        true
                    }
                    else -> false
                }
            }
        }

        dragHandle.setOnTouchListener(makeDragListener(dragHandle))
        collapsedPill.setOnTouchListener(makeDragListener(collapsedPill))
    }

    private fun toggleCollapse() {
        isCollapsed = !isCollapsed
        if (isCollapsed) {
            expandedPanel.visibility = GONE
            collapsedPill.visibility = VISIBLE
        } else {
            expandedPanel.visibility = VISIBLE
            collapsedPill.visibility = GONE
        }
        saveState()
    }

    private fun clampToParent() {
        val maxX = (parentView.width - width).toFloat().coerceAtLeast(0f)
        val maxY = (parentView.height - height).toFloat().coerceAtLeast(0f)
        x = x.coerceIn(0f, maxX)
        y = y.coerceIn(0f, maxY)
    }

    private fun snapToEdge() {
        val parentWidth = parentView.width
        val centerX = x + width / 2
        val targetX = if (centerX < parentWidth / 2) 0f
        else (parentWidth - width).toFloat().coerceAtLeast(0f)
        animate().x(targetX).setDuration(150).start()
    }

    // =========================================================================
    // Opacity
    // =========================================================================

    private fun setupOpacitySlider() {
        opacitySlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                expandedPanel.alpha = (progress / 100f).coerceAtLeast(0.3f)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                saveState()
            }
        })
    }

    private fun toggleOpacitySlider() {
        opacitySlider.visibility = if (opacitySlider.visibility == VISIBLE) GONE else VISIBLE
    }

    // =========================================================================
    // Show / Hide
    // =========================================================================

    fun show() {
        visibility = VISIBLE
    }

    fun hide() {
        visibility = GONE
    }

    val isKeyboardVisible: Boolean get() = visibility == VISIBLE

    // =========================================================================
    // Persistence
    // =========================================================================

    private fun saveState() {
        prefs.edit().apply {
            putFloat(Constants.PREF_KB_POSITION_X, x)
            putFloat(Constants.PREF_KB_POSITION_Y, y)
            putInt(Constants.PREF_KB_OPACITY, opacitySlider.progress)
            putBoolean(Constants.PREF_KB_COLLAPSED, isCollapsed)
            putFloat(Constants.PREF_KB_SCALE, keyScale)
            apply()
        }
    }

    private fun restoreState() {
        val savedX = prefs.getFloat(Constants.PREF_KB_POSITION_X, -1f)
        val savedY = prefs.getFloat(Constants.PREF_KB_POSITION_Y, -1f)
        val savedOpacity = prefs.getInt(Constants.PREF_KB_OPACITY, 100)
        val savedScale = prefs.getFloat(Constants.PREF_KB_SCALE, 1.0f)
        isCollapsed = prefs.getBoolean(Constants.PREF_KB_COLLAPSED, false)

        keyScale = savedScale.coerceIn(0.5f, 1.5f)
        buildKeyboard()

        opacitySlider.progress = savedOpacity
        expandedPanel.alpha = (savedOpacity / 100f).coerceAtLeast(0.3f)

        if (isCollapsed) {
            expandedPanel.visibility = GONE
            collapsedPill.visibility = VISIBLE
        }

        if (savedX >= 0 && savedY >= 0) {
            post {
                x = savedX.coerceAtMost((parentView.width - width).toFloat().coerceAtLeast(0f))
                y = savedY.coerceAtMost((parentView.height - height).toFloat().coerceAtLeast(0f))
            }
        } else {
            post {
                x = ((parentView.width - width) / 2f).coerceAtLeast(0f)
                y = (parentView.height - height).toFloat().coerceAtLeast(0f)
            }
        }
    }

    // =========================================================================
    // Key action types
    // =========================================================================

    sealed class KeyAction {
        enum class Modifier { CTRL, ALT, SHIFT }
        enum class Special { ESC, TAB }
        enum class Shortcut { SAVE, UNDO, REDO, COMMAND_PALETTE, FIND, CLOSE_TAB }
        enum class Navigation { HOME, END, PAGE_UP, PAGE_DOWN, LEFT, RIGHT, UP, DOWN, DELETE }

        data class ModifierToggle(val modifier: Modifier) : KeyAction()
        data class SpecialKey(val key: Special) : KeyAction()
        data class Character(val char: String) : KeyAction()
        data class ShortcutKey(val shortcut: Shortcut) : KeyAction()
        data class NavigationKey(val nav: Navigation) : KeyAction()
        data object Backspace : KeyAction()
        data object Enter : KeyAction()
    }
}
