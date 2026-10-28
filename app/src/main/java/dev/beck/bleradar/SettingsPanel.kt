package dev.beck.bleradar

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** Stillingaspjald fyrir síuna. Hver breyting er send strax í [onChange]. */
class SettingsPanel(
    ctx: Context,
    initial: Filter,
    private val onChange: (Filter) -> Unit,
    private val onClose: (Filter) -> Unit,
) : ScrollView(ctx) {

    private var f = initial
    private val d = resources.displayMetrics.density
    /** Setja hvern reit aftur á gildið í [f], notað af „Núllstilla“. */
    private val refreshers = ArrayList<() -> Unit>()

    init {
        setBackgroundColor(Color.argb(230, 20, 20, 20))
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
        }
        col.addView(text("Sía", 20f, bold = true))

        col.addView(text("Flokkar (ekkert valið = allir)"))
        col.addView(GridLayout(ctx).apply {
            columnCount = 2
            for (k in DeviceKind.entries) {
                addView(CheckBox(ctx).apply {
                    text = "${k.icon} ${k.label}"
                    setTextColor(Color.WHITE)
                    isChecked = k in f.kinds
                    setOnCheckedChangeListener { _, c ->
                        if (c != (k in f.kinds)) { f = f.copy(kinds = if (c) f.kinds + k else f.kinds - k); emit() }
                    }
                    refreshers += { isChecked = k in f.kinds }
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 0
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    }
                })
            }
        })

        col.addView(text("Leita að (nafn, tegund eða framleiðandi, aðskilið með kommu)"))
        col.addView(edit("t.d. aranet, heyrnartól, apple", { f.include }) { f = f.copy(include = it) })

        col.addView(text("Fela (sama snið)"))
        col.addView(edit("t.d. samsung, tæki 4", { f.exclude }) { f = f.copy(exclude = it) })

        col.addView(CheckBox(ctx).apply {
            text = "Bara tæki með nafni"
            setTextColor(Color.WHITE)
            isChecked = f.namedOnly
            setOnCheckedChangeListener { _, c -> f = f.copy(namedOnly = c); emit() }
            refreshers += { isChecked = f.namedOnly }
        })

        col.addView(slider("Lágmarks merkisstyrkur", Filter.RSSI_OFF, -40, { f.minRssi },
            { if (it <= Filter.RSSI_OFF) "allt" else "$it dBm" }) { f = f.copy(minRssi = it) })
        col.addView(slider("Hámarksfjarlægð", 0, 50, { f.maxDistM },
            { if (it == 0) "ótakmarkað" else "$it m" }) { f = f.copy(maxDistM = it) })
        col.addView(slider("Hámarksóvissa", 0, 10, { f.maxSigmaM },
            { if (it == 0) "ótakmarkað" else "± $it m" }) { f = f.copy(maxSigmaM = it) })

        col.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Núllstilla") {
                f = Filter()
                refreshers.forEach { it() }
                emit()
            })
            addView(button("Loka") { close() })
        })
        addView(col)
    }

    fun close() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
        visibility = GONE
        onClose(f)
    }

    private fun emit() = onChange(f)

    private fun text(s: String, size: Float = 14f, bold: Boolean = false) = TextView(context).apply {
        text = s
        textSize = size
        setTextColor(Color.WHITE)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, (10 * d).toInt(), 0, (2 * d).toInt())
    }

    private fun edit(hint: String, get: () -> String, set: (String) -> Unit) = EditText(context).apply {
        this.hint = hint
        setText(get())
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_DONE
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString() ?: ""
                if (v != get()) { set(v); emit() }
            }
        })
        refreshers += { if (text.toString() != get()) setText(get()) }
    }

    private fun slider(
        label: String, min: Int, max: Int, get: () -> Int, fmt: (Int) -> String, set: (Int) -> Unit,
    ): LinearLayout {
        val title = text("")
        fun show(v: Int) { title.text = "$label: ${fmt(v)}" }
        val bar = SeekBar(context).apply {
            this.min = min
            this.max = max
            progress = get()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                    show(v)
                    if (v != get()) { set(v); emit() }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        show(get())
        refreshers += { bar.progress = get(); show(get()) }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(bar)
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { topMargin = (12 * d).toInt(); marginEnd = (8 * d).toInt() }
    }
}
