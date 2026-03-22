package com.codeeditor.app.editor

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.SeekBar
import com.codeeditor.app.R
import com.codeeditor.app.utils.Constants

@SuppressLint("ClickableViewAccessibility")
class FloatingKeyboardView(
    context: Context,
    private val parentView: ViewGroup
) : FrameLayout(context) {

    private val expandedPanel: View
    private val collapsedPill: View
    private val dragHandle: View
    private val opacitySlider: SeekBar
    private val prefs: SharedPreferences =
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    private var isCollapsed = false
    private var isDragging = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var viewStartX = 0f
    private var viewStartY = 0f

    var onKeyPressed: ((KeyAction) -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.layout_floating_keyboard, this, true)

        expandedPanel = findViewById(R.id.expandedPanel)
        collapsedPill = findViewById(R.id.collapsedPill)
        dragHandle = findViewById(R.id.dragHandle)
        opacitySlider = findViewById(R.id.opacitySlider)

        setupDragHandle()
        setupOpacitySlider()
        setupKeyButtons()
        restoreState()
    }

    private fun setupDragHandle() {
        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleCollapse()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                toggleOpacitySlider()
            }
        })

        dragHandle.setOnTouchListener { _, event ->
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
                    if (dx * dx + dy * dy > 25) { // 5px threshold
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

        // Collapsed pill also draggable and tappable
        collapsedPill.setOnTouchListener { _, event ->
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
                    } else if (!isDragging) {
                        toggleCollapse()
                    }
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun setupOpacitySlider() {
        opacitySlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val opacity = progress / 100f
                expandedPanel.alpha = opacity.coerceAtLeast(0.3f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                saveState()
            }
        })
    }

    private fun toggleOpacitySlider() {
        opacitySlider.visibility = if (opacitySlider.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun toggleCollapse() {
        isCollapsed = !isCollapsed
        if (isCollapsed) {
            expandedPanel.visibility = View.GONE
            collapsedPill.visibility = View.VISIBLE
        } else {
            expandedPanel.visibility = View.VISIBLE
            collapsedPill.visibility = View.GONE
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

    private fun setupKeyButtons() {
        // Row 1: Modifiers + Characters
        setupModifierKey(R.id.keyCtrl, KeyAction.Modifier.CTRL)
        setupModifierKey(R.id.keyAlt, KeyAction.Modifier.ALT)
        setupModifierKey(R.id.keyShift, KeyAction.Modifier.SHIFT)

        setupSpecialKey(R.id.keyEsc, KeyAction.Special.ESC)
        setupSpecialKey(R.id.keyTab, KeyAction.Special.TAB)

        setupCharKey(R.id.keyPipe, "|")
        setupCharKey(R.id.keySlash, "/")
        setupCharKey(R.id.keyDash, "-")
        setupCharKey(R.id.keyTilde, "~")
        setupCharKey(R.id.keyUnderscore, "_")
        setupCharKey(R.id.keyColon, ":")
        setupCharKey(R.id.keySemicolon, ";")
        setupCharKey(R.id.keyBraceOpen, "{")
        setupCharKey(R.id.keyBraceClose, "}")
        setupCharKey(R.id.keyBracketOpen, "[")
        setupCharKey(R.id.keyBracketClose, "]")
        setupCharKey(R.id.keyQuote, "'")
        setupCharKey(R.id.keyDoubleQuote, "\"")
        setupCharKey(R.id.keyBackslash, "\\")

        // Row 2: Shortcuts + Navigation
        setupShortcutKey(R.id.keySave, KeyAction.Shortcut.SAVE)
        setupShortcutKey(R.id.keyUndo, KeyAction.Shortcut.UNDO)
        setupShortcutKey(R.id.keyRedo, KeyAction.Shortcut.REDO)
        setupShortcutKey(R.id.keyCmd, KeyAction.Shortcut.COMMAND_PALETTE)
        setupShortcutKey(R.id.keyFind, KeyAction.Shortcut.FIND)
        setupShortcutKey(R.id.keyClose, KeyAction.Shortcut.CLOSE_TAB)

        setupNavKey(R.id.keyHome, KeyAction.Navigation.HOME)
        setupNavKey(R.id.keyEnd, KeyAction.Navigation.END)
        setupNavKey(R.id.keyPgUp, KeyAction.Navigation.PAGE_UP)
        setupNavKey(R.id.keyPgDn, KeyAction.Navigation.PAGE_DOWN)
        setupNavKey(R.id.keyLeft, KeyAction.Navigation.LEFT)
        setupNavKey(R.id.keyRight, KeyAction.Navigation.RIGHT)
        setupNavKey(R.id.keyUp, KeyAction.Navigation.UP)
        setupNavKey(R.id.keyDown, KeyAction.Navigation.DOWN)
        setupNavKey(R.id.keyDel, KeyAction.Navigation.DELETE)
    }

    private fun setupModifierKey(id: Int, modifier: KeyAction.Modifier) {
        findViewById<View>(id).setOnClickListener {
            onKeyPressed?.invoke(KeyAction.ModifierToggle(modifier))
        }
    }

    private fun setupSpecialKey(id: Int, special: KeyAction.Special) {
        findViewById<View>(id).setOnClickListener {
            onKeyPressed?.invoke(KeyAction.SpecialKey(special))
        }
    }

    private fun setupCharKey(id: Int, char: String) {
        findViewById<View>(id).setOnClickListener {
            onKeyPressed?.invoke(KeyAction.Character(char))
        }
    }

    private fun setupShortcutKey(id: Int, shortcut: KeyAction.Shortcut) {
        findViewById<View>(id).setOnClickListener {
            onKeyPressed?.invoke(KeyAction.ShortcutKey(shortcut))
        }
    }

    private fun setupNavKey(id: Int, nav: KeyAction.Navigation) {
        findViewById<View>(id).setOnClickListener {
            onKeyPressed?.invoke(KeyAction.NavigationKey(nav))
        }
    }

    fun updateModifierState(modifier: KeyAction.Modifier, active: Boolean) {
        val viewId = when (modifier) {
            KeyAction.Modifier.CTRL -> R.id.keyCtrl
            KeyAction.Modifier.ALT -> R.id.keyAlt
            KeyAction.Modifier.SHIFT -> R.id.keyShift
        }
        findViewById<View>(viewId).alpha = if (active) 1.0f else 0.5f
    }

    fun show() {
        visibility = View.VISIBLE
    }

    fun hide() {
        visibility = View.GONE
    }

    fun isShown(): Boolean = visibility == View.VISIBLE

    private fun saveState() {
        prefs.edit().apply {
            putFloat(Constants.PREF_KB_POSITION_X, x)
            putFloat(Constants.PREF_KB_POSITION_Y, y)
            putInt(Constants.PREF_KB_OPACITY, opacitySlider.progress)
            putBoolean(Constants.PREF_KB_COLLAPSED, isCollapsed)
            apply()
        }
    }

    private fun restoreState() {
        val savedX = prefs.getFloat(Constants.PREF_KB_POSITION_X, -1f)
        val savedY = prefs.getFloat(Constants.PREF_KB_POSITION_Y, -1f)
        val savedOpacity = prefs.getInt(Constants.PREF_KB_OPACITY, 100)
        isCollapsed = prefs.getBoolean(Constants.PREF_KB_COLLAPSED, false)

        opacitySlider.progress = savedOpacity
        expandedPanel.alpha = (savedOpacity / 100f).coerceAtLeast(0.3f)

        if (isCollapsed) {
            expandedPanel.visibility = View.GONE
            collapsedPill.visibility = View.VISIBLE
        }

        // Position will be applied after layout
        if (savedX >= 0 && savedY >= 0) {
            post {
                x = savedX.coerceAtMost((parentView.width - width).toFloat().coerceAtLeast(0f))
                y = savedY.coerceAtMost((parentView.height - height).toFloat().coerceAtLeast(0f))
            }
        } else {
            // Default: bottom center
            post {
                x = ((parentView.width - width) / 2f).coerceAtLeast(0f)
                y = (parentView.height - height).toFloat().coerceAtLeast(0f)
            }
        }
    }

    // Key action types
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
    }
}
