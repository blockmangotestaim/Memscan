package com.memscan

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {

    private data class AppItem(val label: String, val pkg: String, val icon: Drawable)

    private val bg = Color.parseColor("#0F1216")
    private val cardBg = Color.parseColor("#1A1F26")
    private val accent = Color.parseColor("#3DDC84")
    private val muted = Color.parseColor("#8B95A1")

    private val typeLabels = arrayOf("1 Byte", "2 Bytes", "4 Bytes", "8 Bytes", "Float", "Double")
    private val typeCodes = arrayOf("i8", "i16", "i32", "i64", "f32", "f64")

    private var targetPkg: String? = null
    private var targetLabel: String = ""
    private lateinit var targetIcon: ImageView
    private lateinit var targetName: TextView
    private lateinit var targetPkgView: TextView
    private lateinit var valueInput: EditText
    private lateinit var typeSpinner: Spinner
    private lateinit var fastCheck: CheckBox
    private lateinit var scanBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var resultsAdapter: ArrayAdapter<String>
    private val results = ArrayList<String>()
    private var proc: Process? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int = 14) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(cardBg)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; setTextColor(muted); textSize = 12f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(14), dp(14), dp(14), dp(10))
        }

        root.addView(TextView(this).apply {
            text = "MemScan"; setTextColor(accent); textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "read-only memory scanner • root required"
            setTextColor(muted); textSize = 12f
            setPadding(0, 0, 0, dp(10))
        })

        // ---- Target card ----
        val tCard = card()
        tCard.addView(label("TARGET APP"))
        val tRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        targetIcon = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)) }
        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) }
        }
        targetName = TextView(this).apply { text = "No app selected"; setTextColor(Color.WHITE); textSize = 16f }
        targetPkgView = TextView(this).apply { text = "Tap “Select” to pick a running game/app"; setTextColor(muted); textSize = 12f }
        names.addView(targetName); names.addView(targetPkgView)
        val selectBtn = Button(this).apply { text = "Select"; setOnClickListener { pickApp() } }
        tRow.addView(targetIcon); tRow.addView(names); tRow.addView(selectBtn)
        tCard.addView(tRow)
        root.addView(tCard)

        // ---- Scan card ----
        val sCard = card()
        sCard.addView(label("VALUE"))
        valueInput = EditText(this).apply {
            hint = "e.g. 100"; setTextColor(Color.WHITE); setHintTextColor(muted)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
            typeface = Typeface.MONOSPACE
        }
        sCard.addView(valueInput)
        sCard.addView(label("VALUE TYPE").apply { setPadding(0, dp(8), 0, 0) })
        typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, typeLabels)
            setSelection(2) // 4 Bytes, like Cheat Engine's default
        }
        sCard.addView(typeSpinner)
        fastCheck = CheckBox(this).apply {
            text = "Fast scan (aligned to value size)"; isChecked = true; setTextColor(Color.WHITE)
        }
        sCard.addView(fastCheck)
        scanBtn = Button(this).apply {
            text = "First Scan"; setTextColor(Color.BLACK)
            background = rounded(accent, 10)
            setOnClickListener { startScan() }
        }
        sCard.addView(scanBtn, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        sCard.addView(progress, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        status = TextView(this).apply { text = "Ready."; setTextColor(Color.WHITE); textSize = 14f }
        sCard.addView(status)
        root.addView(sCard)

        // ---- Results ----
        root.addView(label("ADDRESS            VALUE").apply { setPadding(dp(4), 0, 0, dp(4)) })
        resultsAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, results) {
            override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
                val v = super.getView(p, cv, parent) as TextView
                v.typeface = Typeface.MONOSPACE; v.textSize = 13f; v.setTextColor(Color.WHITE)
                return v
            }
        }
        val list = ListView(this).apply {
            adapter = resultsAdapter
            background = rounded(cardBg)
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        root.addView(list)

        setContentView(root)
    }

    // ---------------------------------------------------------------- app picker
    private fun pickApp() {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val all = pm.queryIntentActivities(q, 0)
            .map { AppItem(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm)) }
            .filter { it.pkg != packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }

        var shown = all
        lateinit var dialog: AlertDialog
        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(p: Int) = shown[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
                val a = shown[p]
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                }
                row.addView(ImageView(this@MainActivity).apply {
                    setImageDrawable(a.icon); layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                })
                val col = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) }
                }
                col.addView(TextView(this@MainActivity).apply { text = a.label; textSize = 15f })
                col.addView(TextView(this@MainActivity).apply { text = a.pkg; textSize = 11f; setTextColor(muted) })
                row.addView(col)
                return row
            }
        }
        val search = EditText(this).apply { hint = "Search apps…"; setSingleLine() }
        val lv = ListView(this).apply {
            this.adapter = adapter
            setOnItemClickListener { _, _, pos, _ ->
                val a = shown[pos]
                targetPkg = a.pkg; targetLabel = a.label
                targetIcon.setImageDrawable(a.icon)
                targetName.text = a.label; targetPkgView.text = a.pkg
                dialog.dismiss()
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val t = s.toString().trim().lowercase()
                shown = if (t.isEmpty()) all else all.filter { it.label.lowercase().contains(t) || it.pkg.lowercase().contains(t) }
                adapter.notifyDataSetChanged()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), 0)
            addView(search)
            addView(lv, LinearLayout.LayoutParams(-1, dp(420)))
        }
        dialog = AlertDialog.Builder(this).setTitle("Select target app").setView(box)
            .setNegativeButton("Cancel", null).create()
        dialog.show()
    }

    // ---------------------------------------------------------------- scanning
    private fun startScan() {
        val pkg = targetPkg ?: run { toast("Select a target app first"); return }
        val raw = valueInput.text.toString().trim()
        val type = typeCodes[typeSpinner.selectedItemPosition]
        val isInt = type.startsWith("i")
        val okFmt = if (isInt) Regex("^-?[0-9]+$") else Regex("^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$")
        if (!okFmt.matches(raw)) { toast(if (isInt) "Enter a whole number" else "Enter a valid number"); return }

        val apk = applicationInfo.sourceDir
        val aligned = if (fastCheck.isChecked) "1" else "0"
        val cmd = "set -- \$(pidof $pkg); " +
                "[ -z \"\$1\" ] && { echo NOTRUNNING; exit 3; }; " +
                "CLASSPATH='$apk' exec app_process /system/bin com.memscan.Scanner \$1 $type $raw $aligned"

        results.clear(); resultsAdapter.notifyDataSetChanged()
        scanBtn.isEnabled = false; scanBtn.text = "Scanning…"
        progress.progress = 0
        status.text = "Scanning $targetLabel…"

        Thread {
            var count = -1L
            var scannedBytes = 0L
            val found = ArrayList<String>()
            val errText = StringBuilder()
            var notRunning = false
            try {
                val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
                proc = p
                p.outputStream.close()
                p.inputStream.bufferedReader().forEachLine { l ->
                    when {
                        l.startsWith("P ") -> {
                            val pct = l.substring(2).trim().toIntOrNull() ?: 0
                            runOnUiThread { progress.progress = pct }
                        }
                        l.startsWith("R ") -> {
                            val parts = l.substring(2).split(" ", limit = 2)
                            found.add(String.format("%-18s %s", parts[0], parts.getOrElse(1) { "" }))
                        }
                        l.startsWith("DONE ") -> {
                            val t = l.split(" ")
                            count = t[1].toLong(); scannedBytes = t[2].toLong()
                        }
                        l == "NOTRUNNING" -> notRunning = true
                        else -> if (errText.length < 400) errText.append(l).append('\n')
                    }
                }
                p.waitFor()
            } catch (e: Exception) {
                errText.append("Could not run su — is this device rooted?\n${e.message}")
            }
            runOnUiThread {
                scanBtn.isEnabled = true; scanBtn.text = "First Scan"
                results.addAll(found); resultsAdapter.notifyDataSetChanged()
                status.text = when {
                    notRunning -> "$targetLabel isn't running. Launch it first, then scan."
                    count >= 0 -> {
                        val mb = scannedBytes / (1024 * 1024)
                        "Found ${"%,d".format(count)} match${if (count == 1L) "" else "es"}  (scanned $mb MB)" +
                                if (count > found.size) "\nShowing first ${found.size}" else ""
                    }
                    else -> "Scan failed:\n$errText"
                }
            }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() { proc?.destroy(); super.onDestroy() }
}
