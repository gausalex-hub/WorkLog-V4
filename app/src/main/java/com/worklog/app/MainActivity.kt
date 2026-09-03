package com.worklog.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.location.Location
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.gms.location.LocationServices
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class WorkEntry(
    val id: Long,
    val start: Long,
    val end: Long,
    val type: String = "עבודה",
    val place: String = "משרד",
    val lat: Double? = null,
    val lon: Double? = null,
    val note: String = ""
)

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("worklog", MODE_PRIVATE) }
    private val locationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private var entries = mutableListOf<WorkEntry>()
    private var activeStart: Long? = null
    private var activePlace = "משרד"
    private var officeLat: Double? = null
    private var officeLon: Double? = null
    private lateinit var content: LinearLayout
    private var displayedMonth = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }

    private var pendingVoiceCallback: ((String) -> Unit)? = null

    private val speechLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (text != null) pendingVoiceCallback?.invoke(text)
            pendingVoiceCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadData()
        showHome()
    }

    private fun loadData() {
        activeStart = prefs.getLong("activeStart", -1L).takeIf { it > 0 }
        activePlace = prefs.getString("activePlace", "משרד") ?: "משרד"
        officeLat = prefs.getString("officeLat", null)?.toDoubleOrNull()
        officeLon = prefs.getString("officeLon", null)?.toDoubleOrNull()

        val json = JSONArray(prefs.getString("entries", "[]") ?: "[]")
        entries.clear()

        for (i in 0 until json.length()) {
            val o = json.getJSONObject(i)
            entries.add(
                WorkEntry(
                    id = o.getLong("id"),
                    start = o.getLong("start"),
                    end = o.getLong("end"),
                    type = o.optString("type", "עבודה"),
                    place = o.optString("place", "משרד"),
                    lat = if (o.has("lat") && !o.isNull("lat")) o.getDouble("lat") else null,
                    lon = if (o.has("lon") && !o.isNull("lon")) o.getDouble("lon") else null,
                    note = o.optString("note", "")
                )
            )
        }
        entries.sortBy { it.start }
    }

    private fun saveData() {
        val array = JSONArray()
        entries.forEach { e ->
            array.put(JSONObject().apply {
                put("id", e.id)
                put("start", e.start)
                put("end", e.end)
                put("type", e.type)
                put("place", e.place)
                if (e.lat != null) put("lat", e.lat)
                if (e.lon != null) put("lon", e.lon)
                put("note", e.note)
            })
        }
        prefs.edit().putString("entries", array.toString()).apply()
    }

    private fun showHome() {
        setBase("WorkLog")

        val today = Calendar.getInstance()
        val todayEntries = entriesForDay(today)
        val workToday = todayEntries.filter { it.type == "עבודה" }
        val total = workToday.sumOf { maxOf(0L, it.end - it.start) }
        val office = workToday.filter { it.place == "משרד" }.sumOf { maxOf(0L, it.end - it.start) }

        addTitle("היום")
        addText("סה״כ עבודה: ${formatDuration(total)}    משרד: ${formatDuration(office)}    חוץ: ${formatDuration(total - office)}")

        val special = todayEntries.filter { it.type != "עבודה" }
        if (special.any { it.type == "חופש" }) addText("🏖️ יום חופש")
        if (special.any { it.type == "מחלה" }) addText("🤒 יום מחלה")

        val startButton = Button(this).apply {
            text = if (activeStart == null) "▶ התחל עבודה" else "עבודה פעילה מאז ${formatDate(activeStart!!)}"
            isEnabled = activeStart == null
            setOnClickListener { showStartDialog() }
        }
        content.addView(startButton)

        if (activeStart != null) {
            addButton("■ סיים עבודה") { showFinishDialog() }
        }

        addButton("＋ רישום פעולה") { showEntryDialog(null, Calendar.getInstance()) }
        addButton("📅 יומן") { showCalendar() }
        addButton("📊 דוחות") { showReports() }
        addButton("⚙ הגדרות") { showSettings() }

        addTitle("רשומות היום")
        if (todayEntries.isEmpty()) addText("אין רשומות להיום")
        todayEntries.sortedByDescending { it.start }.forEach { addEntryCard(it) }
    }

    private fun showCalendar() {
        displayedMonth = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
        showCalendarMonth(displayedMonth, Calendar.getInstance())
    }

    private fun showCalendarAt(time: Long) {
        val selected = Calendar.getInstance().apply { timeInMillis = time }
        displayedMonth = (selected.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
        showCalendarMonth(displayedMonth, selected)
    }

    private fun showCalendarMonth(month: Calendar, initiallySelected: Calendar) {
        setBase("יומן עבודה")

        val minMonth = Calendar.getInstance().apply {
            add(Calendar.YEAR, -2)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val maxMonth = Calendar.getInstance().apply {
            add(Calendar.YEAR, 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }

        val info = TextView(this).apply {
            text = "דפדף בין חודשים ולחץ על יום כדי לראות, להוסיף או לערוך רשומות."
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 12)
        }
        content.addView(info)

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val prev = Button(this).apply { text = "‹"; textSize = 28f }
        val title = TextView(this).apply {
            textSize = 22f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val next = Button(this).apply { text = "›"; textSize = 28f }
        nav.addView(prev, LinearLayout.LayoutParams(56, 52))
        nav.addView(title, LinearLayout.LayoutParams(0, 52, 1f))
        nav.addView(next, LinearLayout.LayoutParams(56, 52))
        content.addView(nav)

        val weekdays = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(0, 6, 0, 2)
        }
        val names = arrayOf("א׳", "ב׳", "ג׳", "ד׳", "ה׳", "ו׳", "ש׳")
        names.forEach { name ->
            weekdays.addView(TextView(this).apply {
                text = name
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(Color.DKGRAY)
            }, LinearLayout.LayoutParams(0, 34, 1f))
        }
        content.addView(weekdays)

        val grid = GridLayout(this).apply {
            columnCount = 7
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            useDefaultMargins = false
        }
        content.addView(grid, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val selectedTitle = TextView(this).apply {
            textSize = 19f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 6)
        }
        content.addView(selectedTitle)

        val dayBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(dayBox)

        fun sameDay(a: Calendar, b: Calendar): Boolean =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

        fun refreshDay(selected: Calendar) {
            selectedTitle.text = "רשומות ל־${formatDay(selected.timeInMillis)}"
            dayBox.removeAllViews()
            val dayEntries = entriesForDay(selected)
            if (dayEntries.isEmpty()) {
                dayBox.addView(TextView(this).apply {
                    text = "אין רשומות ביום זה"
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setPadding(0, 10, 0, 10)
                })
            } else {
                dayEntries.sortedByDescending { it.start }.forEach { entry ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(14, 10, 14, 10)
                        background = roundedBackground("#F2F6F6", 14)
                    }
                    row.addView(TextView(this).apply {
                        text = entrySummary(entry)
                        textSize = 16f
                    })
                    row.addView(Button(this).apply {
                        text = "ערוך רשומה"
                        setOnClickListener { showEntryDialog(entry, selected) }
                    })
                    dayBox.addView(row, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 4, 0, 4) })
                }
            }
            dayBox.addView(Button(this).apply {
                text = "＋ הוסף רשומה ליום זה"
                setOnClickListener { showEntryDialog(null, selected) }
            })
        }

        var selectedDay = (initiallySelected.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }

        fun render() {
            title.text = SimpleDateFormat("MMMM yyyy", Locale("he", "IL")).format(month.time)
            prev.isEnabled = month.after(minMonth)
            next.isEnabled = month.before(maxMonth)
            grid.removeAllViews()

            val first = (month.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
            val firstColumn = first.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
            val daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH)
            val cells = ((firstColumn + daysInMonth + 6) / 7) * 7
            val cellHeight = if (cells > 35) 48 else 54

            repeat(cells) { index ->
                val dayNumber = index - firstColumn + 1
                val cell = TextView(this).apply {
                    gravity = Gravity.CENTER
                    textSize = 17f
                    setPadding(2, 2, 2, 2)
                }
                val params = GridLayout.LayoutParams().apply {
                    width = 0
                    height = dp(cellHeight)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
                }
                if (dayNumber in 1..daysInMonth) {
                    val dayCal = (month.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, dayNumber) }
                    val hasEntries = entriesForDay(dayCal).isNotEmpty()
                    val isSelected = sameDay(dayCal, selectedDay)
                    cell.text = buildString {
                        append(dayNumber)
                        if (hasEntries) append(" •")
                    }
                    if (isSelected) {
                        cell.setTextColor(Color.WHITE)
                        cell.background = roundedBackground("#0F766E", 22)
                    } else if (hasEntries) {
                        cell.setTextColor(Color.rgb(15, 118, 110))
                    }
                    cell.setOnClickListener {
                        selectedDay = dayCal
                        render()
                        refreshDay(selectedDay)
                    }
                }
                grid.addView(cell, params)
            }
            refreshDay(selectedDay)
        }

        prev.setOnClickListener {
            month.add(Calendar.MONTH, -1)
            selectedDay = (month.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
            render()
        }
        next.setOnClickListener {
            month.add(Calendar.MONTH, 1)
            selectedDay = (month.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
            render()
        }

        render()
        addButton("← חזרה") { showHome() }
    }

    private fun showStartDialog() {
        val choices = arrayOf("משרד", "מחוץ למשרד")
        AlertDialog.Builder(this)
            .setTitle("התחלת עבודה")
            .setSingleChoiceItems(choices, if (activePlace == "משרד") 0 else 1) { dialog, which ->
                activePlace = choices[which]
                activeStart = System.currentTimeMillis()
                prefs.edit()
                    .putLong("activeStart", activeStart!!)
                    .putString("activePlace", activePlace)
                    .apply()
                dialog.dismiss()
                showHome()
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun showFinishDialog() {
        val box = EditText(this).apply { hint = "הערה (אופציונלי)" }

        AlertDialog.Builder(this)
            .setTitle("סיום עבודה")
            .setView(box)
            .setPositiveButton("סיים ושמור") { _, _ ->
                val start = activeStart ?: return@setPositiveButton
                addEntry(
                    WorkEntry(
                        id = System.currentTimeMillis(),
                        start = start,
                        end = System.currentTimeMillis(),
                        type = "עבודה",
                        place = activePlace,
                        note = box.text.toString().trim()
                    )
                )
                activeStart = null
                prefs.edit().remove("activeStart").apply()
                showHome()
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun showEntryDialog(existing: WorkEntry?, selectedDay: Calendar? = null) {
        val base = selectedDay ?: Calendar.getInstance().apply {
            timeInMillis = existing?.start ?: System.currentTimeMillis()
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 4, 28, 4)
        }

        val typeSpinner = Spinner(this)
        val types = arrayOf("עבודה", "חופש", "מחלה")
        typeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, types)
        typeSpinner.setSelection(types.indexOf(existing?.type ?: "עבודה").coerceAtLeast(0))

        val date = EditText(this).apply {
            hint = "תאריך (dd/MM/yyyy)"
            setText(formatDay(existing?.start ?: base.timeInMillis))
        }

        val start = EditText(this).apply {
            hint = "שעת התחלה (HH:mm)"
            setText(formatTime(existing?.start ?: base.timeInMillis))
        }

        val end = EditText(this).apply {
            hint = "שעת סיום (HH:mm)"
            val defaultEnd = existing?.end ?: base.timeInMillis
            setText(formatTime(defaultEnd))
        }

        val placeSpinner = Spinner(this)
        placeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf("משרד", "מחוץ למשרד")
        )
        placeSpinner.setSelection(if ((existing?.place ?: "משרד") == "משרד") 0 else 1)

        val note = EditText(this).apply {
            hint = "תיאור / הערה"
            setText(existing?.note ?: "")
            minLines = 3
        }

        val voice = Button(this).apply {
            text = "🎤 הכתבה קולית"
            setOnClickListener {
                startVoice { spoken ->
                    note.setText((note.text.toString() + " " + spoken).trim())
                }
            }
        }

        val gps = Button(this).apply {
            text = "📍 קבל מיקום GPS"
            setOnClickListener {
                getLocation { loc ->
                    tag = loc
                    text = if (loc == null) "GPS לא זמין" else "GPS נקלט ✓"
                }
            }
        }

        layout.addView(label("סוג הרשומה"))
        layout.addView(typeSpinner)
        layout.addView(date)
        layout.addView(start)
        layout.addView(end)
        layout.addView(placeSpinner)
        layout.addView(note)
        layout.addView(voice)
        layout.addView(gps)

        fun updateVisibility() {
            val isWork = typeSpinner.selectedItem.toString() == "עבודה"
            start.visibility = if (isWork) View.VISIBLE else View.GONE
            end.visibility = if (isWork) View.VISIBLE else View.GONE
            placeSpinner.visibility = if (isWork) View.VISIBLE else View.GONE
            gps.visibility = if (isWork) View.VISIBLE else View.GONE
        }

        typeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateVisibility()
            }
        }
        updateVisibility()

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "רישום חדש" else "עריכת רשומה")
            .setView(layout)
            .setPositiveButton("שמור", null)
            .setNegativeButton("ביטול", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val day = parseDay(date.text.toString())
                if (day == null) {
                    Toast.makeText(this, "תאריך לא תקין. השתמש ב־dd/MM/yyyy", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                val type = typeSpinner.selectedItem.toString()
                val s: Long
                val e: Long

                if (type == "עבודה") {
                    s = parseDateTime(date.text.toString(), start.text.toString()) ?: -1L
                    e = parseDateTime(date.text.toString(), end.text.toString()) ?: -1L

                    if (s < 0 || e < 0) {
                        Toast.makeText(this, "שעה לא תקינה. השתמש ב־HH:mm", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    if (e < s) {
                        Toast.makeText(this, "שעת הסיום חייבת להיות אחרי שעת ההתחלה", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                } else {
                    val c = Calendar.getInstance().apply { timeInMillis = day }
                    c.set(Calendar.HOUR_OF_DAY, 12)
                    c.set(Calendar.MINUTE, 0)
                    s = c.timeInMillis
                    e = s
                }

                val loc = gps.tag as? Location
                val entry = WorkEntry(
                    id = existing?.id ?: System.currentTimeMillis(),
                    start = s,
                    end = e,
                    type = type,
                    place = if (type == "עבודה") placeSpinner.selectedItem.toString() else "",
                    lat = if (type == "עבודה") loc?.latitude ?: existing?.lat else null,
                    lon = if (type == "עבודה") loc?.longitude ?: existing?.lon else null,
                    note = note.text.toString().trim()
                )

                if (existing == null) addEntry(entry) else updateEntry(entry)
                dialog.dismiss()
                showCalendarAt(day)
            }
        }

        dialog.show()
    }

    private fun label(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 15f
            setPadding(0, 8, 0, 2)
        }

    private fun addEntry(entry: WorkEntry) {
        entries.add(entry)
        entries.sortBy { it.start }
        saveData()
    }

    private fun updateEntry(entry: WorkEntry) {
        val index = entries.indexOfFirst { it.id == entry.id }
        if (index >= 0) entries[index] = entry
        entries.sortBy { it.start }
        saveData()
    }

    private fun addEntryCard(entry: WorkEntry) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 12, 18, 12)
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
        }

        box.addView(TextView(this).apply {
            text = entrySummary(entry)
            textSize = 16f
        })

        box.addView(Button(this).apply {
            text = "ערוך"
            setOnClickListener { showEntryDialog(entry) }
        })

        box.addView(Button(this).apply {
            text = "מחק"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("מחיקת רשומה")
                    .setMessage("למחוק את הרשומה?")
                    .setPositiveButton("מחק") { _, _ ->
                        entries.removeAll { it.id == entry.id }
                        saveData()
                        showHome()
                    }
                    .setNegativeButton("ביטול", null)
                    .show()
            }
        })

        content.addView(box)
    }

    private fun entrySummary(entry: WorkEntry): String {
        return when (entry.type) {
            "חופש" -> "🏖️ יום חופש\n${entry.note}"
            "מחלה" -> "🤒 יום מחלה\n${entry.note}"
            else -> "${formatDate(entry.start)} – ${formatDate(entry.end)}\n${entry.place} • ${formatDuration(entry.end - entry.start)}\n${entry.note}"
        }
    }

    private fun showReports() {
        setBase("דוחות")

        val now = Calendar.getInstance()
        val monthEntries = entries.filter {
            val c = Calendar.getInstance().apply { timeInMillis = it.start }
            c.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                    c.get(Calendar.MONTH) == now.get(Calendar.MONTH)
        }
        val yearEntries = entries.filter {
            Calendar.getInstance().apply { timeInMillis = it.start }
                .get(Calendar.YEAR) == now.get(Calendar.YEAR)
        }

        fun workMs(list: List<WorkEntry>) =
            list.filter { it.type == "עבודה" }.sumOf { maxOf(0L, it.end - it.start) }

        fun officeMs(list: List<WorkEntry>) =
            list.filter { it.type == "עבודה" && it.place == "משרד" }
                .sumOf { maxOf(0L, it.end - it.start) }

        fun outsideMs(list: List<WorkEntry>) =
            list.filter { it.type == "עבודה" && it.place == "מחוץ למשרד" }
                .sumOf { maxOf(0L, it.end - it.start) }

        fun days(list: List<WorkEntry>, type: String): Int =
            list.filter { it.type == type }
                .map { formatDay(it.start) }
                .distinct()
                .size

        addTitle("החודש")
        addText("סה״כ עבודה: ${formatDuration(workMs(monthEntries))}")
        addText("משרד: ${formatDuration(officeMs(monthEntries))}")
        addText("מחוץ למשרד: ${formatDuration(outsideMs(monthEntries))}")
        addText("🏖️ ימי חופש: ${days(monthEntries, "חופש")}")
        addText("🤒 ימי מחלה: ${days(monthEntries, "מחלה")}")

        addTitle("השנה")
        addText("סה״כ עבודה: ${formatDuration(workMs(yearEntries))}")
        addText("משרד: ${formatDuration(officeMs(yearEntries))}")
        addText("מחוץ למשרד: ${formatDuration(outsideMs(yearEntries))}")
        addText("🏖️ ימי חופש: ${days(yearEntries, "חופש")}")
        addText("🤒 ימי מחלה: ${days(yearEntries, "מחלה")}")

        addButton("📄 PDF + שיתוף") { exportPdf() }
        addButton("📊 CSV / Excel + שיתוף") { exportCsv() }
        addButton("💬 שיתוף סיכום") {
            shareText(
                "WorkLog\n" +
                        "החודש – עבודה: ${formatDuration(workMs(monthEntries))}, " +
                        "חופש: ${days(monthEntries, "חופש")}, " +
                        "מחלה: ${days(monthEntries, "מחלה")}\n" +
                        "השנה – עבודה: ${formatDuration(workMs(yearEntries))}, " +
                        "חופש: ${days(yearEntries, "חופש")}, " +
                        "מחלה: ${days(yearEntries, "מחלה")}"
            )
        }
        addButton("← חזרה") { showHome() }
    }

    private fun showSettings() {
        setBase("הגדרות")
        addText("משרד: " + if (officeLat == null) "לא הוגדר" else "%.5f, %.5f".format(officeLat, officeLon))

        addButton("📍 הגדר את המיקום הנוכחי כמשרד") {
            getLocation { loc ->
                if (loc != null) {
                    officeLat = loc.latitude
                    officeLon = loc.longitude
                    prefs.edit()
                        .putString("officeLat", loc.latitude.toString())
                        .putString("officeLon", loc.longitude.toString())
                        .apply()
                    Toast.makeText(this, "המשרד הוגדר", Toast.LENGTH_SHORT).show()
                    showSettings()
                } else {
                    Toast.makeText(this, "לא ניתן לקבל GPS", Toast.LENGTH_SHORT).show()
                }
            }
        }

        addButton("🎤 בקש הרשאת מיקרופון + GPS") {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION),
                100
            )
        }

        addButton("← חזרה") { showHome() }
    }

    private fun startVoice(callback: (String) -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingVoiceCallback = callback
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "דבר עכשיו")
        }
        pendingVoiceCallback = callback
        speechLauncher.launch(intent)
    }

    private fun getLocation(callback: (Location?) -> Unit) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 102)
            callback(null)
            return
        }
        locationClient.lastLocation.addOnSuccessListener(callback)
    }

    private fun exportCsv() {
        val header = "\uFEFFסוג,תאריך התחלה,תאריך סיום,מיקום,קו רוחב,קו אורך,תיאור\n"
        val body = entries.joinToString("\n") {
            "${it.type},${formatDate(it.start)},${formatDate(it.end)},${it.place},${it.lat ?: ""},${it.lon ?: ""},\"${it.note.replace("\"", "\"\"")}\""
        }
        shareFile("worklog.csv", "text/csv", (header + body).toByteArray(Charsets.UTF_8))
    }

    private fun exportPdf() {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val paint = Paint().apply { textSize = 11f }
        var y = 35f

        page.canvas.drawText("WorkLog report", 30f, y, paint)
        y += 22
        val workTotal = entries.filter { it.type == "עבודה" }.sumOf { maxOf(0L, it.end - it.start) }
        page.canvas.drawText("Total work: ${formatDuration(workTotal)}", 30f, y, paint)
        y += 22

        entries.takeLast(35).forEach {
            if (y < 810f) {
                val line = when (it.type) {
                    "עבודה" -> "${formatDate(it.start)} | ${it.place} | ${formatDuration(it.end - it.start)} | ${it.note}"
                    else -> "${formatDay(it.start)} | ${it.type} | ${it.note}"
                }
                page.canvas.drawText(line.take(110), 30f, y, paint)
                y += 17
            }
        }

        doc.finishPage(page)
        val file = File(cacheDir, "worklog.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        shareFile("worklog.pdf", "application/pdf", file.readBytes())
    }

    private fun shareText(text: String) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                "שיתוף"
            )
        )
    }

    private fun shareFile(name: String, type: String, bytes: ByteArray) {
        val file = File(cacheDir, name)
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)

        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    this.type = type
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "שיתוף"
            )
        )
    }

    private fun setBase(title: String) {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(250, 251, 252))
            setOnApplyWindowInsetsListener { view, insets ->
                val systemBars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(0, systemBars.top, 0, systemBars.bottom)
                insets
            }
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(12))
            background = roundedBackground("#FFFFFF", 0)
            elevation = dp(2).toFloat()
        }

        bar.addView(TextView(this).apply {
            text = title
            textSize = 25f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.rgb(30, 41, 59))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        root.addView(bar)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(24))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        setContentView(root)
        root.requestApplyInsets()
    }

    private fun addTitle(text: String) {
        content.addView(TextView(this).apply {
            this.text = text
            textSize = 21f
            setTextColor(Color.rgb(30, 41, 59))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(6))
        })
    }

    private fun addText(text: String) {
        content.addView(TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.rgb(71, 85, 105))
            setPadding(0, dp(5), 0, dp(5))
        })
    }

    private fun addButton(text: String, action: () -> Unit) {
        content.addView(Button(this).apply {
            this.text = text
            textSize = 17f
            minHeight = dp(48)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(4), 0, dp(4)) })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun roundedBackground(color: String, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.parseColor(color))
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun entriesForDay(day: Calendar): List<WorkEntry> {
        val start = Calendar.getInstance().apply {
            timeInMillis = day.timeInMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val end = start + 24L * 60L * 60L * 1000L
        return entries.filter { it.start >= start && it.start < end }
    }

    private fun formatDate(time: Long): String =
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(time))

    private fun formatDay(time: Long): String =
        SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(time))

    private fun formatTime(time: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time))

    private fun parseDay(value: String): Long? =
        try {
            SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).apply {
                isLenient = false
            }.parse(value)?.time
        } catch (_: Exception) {
            null
        }

    private fun parseDateTime(day: String, time: String): Long? =
        try {
            SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).apply {
                isLenient = false
            }.parse("$day $time")?.time
        } catch (_: Exception) {
            null
        }

    private fun formatDuration(ms: Long): String {
        val minutes = ms / 60000L
        return "%02d:%02d".format(minutes / 60L, minutes % 60L)
    }
}