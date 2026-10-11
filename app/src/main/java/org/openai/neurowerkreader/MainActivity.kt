package org.openai.neurowerkreader

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/** Experimental reverse-engineering viewer. Not validated for clinical interpretation. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var plot: WaveView
    private lateinit var channelSpinner: Spinner
    private lateinit var layoutSpinner: Spinner
    private var recording: Recording? = null
    private val openCode = 42

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 18, 18, 12); setBackgroundColor(Color.WHITE) }
        val title = TextView(this).apply { text = "Neurowerk EEG Reader — experimental"; textSize = 21f; setTextColor(Color.rgb(25,45,65)); setPadding(0,0,0,8) }
        root.addView(title)
        val warning = TextView(this).apply { text = "Research prototype only. Channel mapping and calibration are not clinically validated."; textSize = 13f; setTextColor(Color.rgb(150,60,20)); setPadding(0,0,0,10) }
        root.addView(warning)
        val open = Button(this).apply { text = "Open .EEG file"; setOnClickListener { val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }; startActivityForResult(i, openCode) } }
        root.addView(open)
        status = TextView(this).apply { text = "Choose a Neurowerk .EEG file."; textSize = 14f; setTextColor(Color.DKGRAY); setPadding(0,10,0,10) }
        root.addView(status)
        channelSpinner = Spinner(this)
        root.addView(channelSpinner)
        layoutSpinner = Spinner(this)
        layoutSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Interleaved samples (test)", "Channel-major blocks (test)"))
        layoutSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { refreshPlot() }
        }
        root.addView(layoutSpinner)
        plot = WaveView(this)
        root.addView(plot, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val prev = Button(this).apply { text = "◀ 10 s"; setOnClickListener { plot.shiftSeconds(-10.0) } }
        val next = Button(this).apply { text = "10 s ▶"; setOnClickListener { plot.shiftSeconds(10.0) } }
        controls.addView(prev, LinearLayout.LayoutParams(0, -2, 1f)); controls.addView(next, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(controls)
        setContentView(root)
        channelSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { refreshPlot() }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != openCode || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        status.text = "Reading file…"
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val parsed = Recording.inspect(bytes, queryName(uri))
                runOnUiThread {
                    recording = parsed
                    val labels = (0 until parsed.channels).map { i -> parsed.labels.getOrNull(i) ?: "Signal ${i+1}" }
                    channelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
                    val duration = parsed.samples.toDouble() / parsed.samplingRate
                    status.text = "${parsed.fileName}\n${parsed.channels} signals • ${parsed.samplingRate} Hz • ${parsed.samples} samples/channel • ${"%.1f".format(duration)} s\nData start offset: ${parsed.dataOffset} bytes • ${parsed.sampleFormat}"
                    plot.secondsOffset = 0.0
                    refreshPlot()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Could not inspect this file: ${e.message}" }
            }
        }.start()
    }

    private fun queryName(uri: Uri): String = uri.lastPathSegment?.substringAfterLast('/') ?: "Neurowerk recording"

    private fun refreshPlot() {
        val r = recording ?: return
        val ch = channelSpinner.selectedItemPosition.coerceAtLeast(0)
        val interleaved = layoutSpinner.selectedItemPosition == 0
        plot.setData(r, ch, interleaved)
    }
}

data class Recording(val bytes: ByteArray, val fileName: String, val dataOffset: Int, val channels: Int, val samples: Int, val samplingRate: Int, val labels: List<String>, val sampleFormat: String) {
    companion object {
        fun inspect(b: ByteArray, name: String): Recording {
            require(b.size > 5000) { "File is too small to be a Neurowerk recording" }
            val headerText = String(b, 0, min(b.size, 12000), Charsets.ISO_8859_1)
            require(headerText.startsWith("V3.0")) { "Expected Neurowerk V3.0 signature" }
            // The provided sample has 388583 samples × 22 channels × 4 bytes and a 4599-byte header.
            // Derive candidate offset from exact file-size arithmetic; do not assume every file matches.
            val candidateSamples = Regex("(?<![0-9])([0-9]{5,7})(?![0-9])").findAll(headerText).mapNotNull { it.groupValues[1].toIntOrNull() }.firstOrNull { it in 1000..10000000 }
                ?: 388583
            val possibleChannels = listOf(22, 21, 20, 24, 32, 19, 18)
            var channels = 22
            var samples = candidateSamples
            var offset = -1
            for (c in possibleChannels) {
                val rem = b.size - c * samples * 4
                if (rem in 100..20000) { channels = c; offset = rem; break }
            }
            if (offset < 0) {
                // Find exact header/data boundary for a 22-channel float32 payload, if possible.
                channels = 22
                val payload = channels.toLong() * samples * 4L
                if (payload < b.size && b.size - payload in 0..65536) offset = (b.size - payload).toInt()
            }
            require(offset >= 0 && offset < b.size) { "Could not infer data offset / sample count / channel count from this file" }
            val labels = Regex("(?m)(?:^|[^A-Za-z0-9])((?:Fp|F|C|T|P|O|A|ECG|EOG|EMG)[A-Za-z0-9-]{0,8})(?:\\u0000|[^A-Za-z0-9-])")
                .findAll(headerText).map { it.groupValues[1] }.distinct().take(channels).toList()
            return Recording(b, name, offset, channels, samples, 256, labels, "32-bit float candidate (unverified)")
        }
    }
}

class WaveView(ctx: android.content.Context) : View(ctx) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 90, 145); strokeWidth = 2f; style = Paint.Style.STROKE }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(225, 230, 235); strokeWidth = 1f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 28f }
    private var rec: Recording? = null
    private var channel = 0
    private var interleaved = true
    var secondsOffset = 0.0
    fun setData(r: Recording, ch: Int, inter: Boolean) { rec = r; channel = ch; interleaved = inter; invalidate() }
    fun shiftSeconds(delta: Double) { val r = rec ?: return; secondsOffset = (secondsOffset + delta).coerceIn(0.0, max(0.0, r.samples.toDouble()/r.samplingRate - 10.0)); invalidate() }

    override fun onDraw(c: Canvas) {
        super.onDraw(c); c.drawColor(Color.WHITE)
        val r = rec
        if (r == null) { c.drawText("Waveform preview appears here", 24f, 55f, text); return }
        val left = 55f; val top = 35f; val right = width - 20f; val bottom = height - 45f
        for (i in 0..10) { val y = top + (bottom-top)*i/10f; c.drawLine(left,y,right,y,grid) }
        for (i in 0..10) { val x = left + (right-left)*i/10f; c.drawLine(x,top,x,bottom,grid) }
        val start = (secondsOffset*r.samplingRate).toInt().coerceIn(0, r.samples-1)
        val count = min(r.samplingRate*10, r.samples-start)
        val stride = max(1, count / max(1, (right-left).toInt()))
        val vals = ArrayList<Float>()
        val bb = ByteBuffer.wrap(r.bytes).order(ByteOrder.LITTLE_ENDIAN)
        var i = start
        while (i < start + count) {
            val idx = if (interleaved) r.dataOffset.toLong() + ((i.toLong()*r.channels + channel)*4L) else r.dataOffset.toLong() + ((channel.toLong()*r.samples + i)*4L)
            if (idx >= 0 && idx+4 <= r.bytes.size) vals.add(bb.getFloat(idx.toInt()))
            i += stride
        }
        val finite = vals.filter { it.isFinite() }
        if (finite.size < 2) { c.drawText("No finite samples at this channel/layout", left, top+35, text); return }
        val minV = finite.minOrNull()!!; val maxV = finite.maxOrNull()!!
        val range = max(1e-6f, maxV-minV)
        var prevX = left; var prevY = (top+bottom)/2f
        for (j in vals.indices) {
            val v = vals[j]; if (!v.isFinite()) continue
            val x = left + (right-left)*j/max(1, vals.size-1)
            val y = bottom - ((v-minV)/range)*(bottom-top)
            if (j > 0) c.drawLine(prevX, prevY, x, y, line)
            prevX = x; prevY = y
        }
        c.drawText("Ch ${channel+1} • ${"%.1f".format(secondsOffset)}–${"%.1f".format(secondsOffset+count.toDouble()/r.samplingRate)} s", left, height-12f, text)
        c.drawText("min ${"%.3g".format(minV)}  max ${"%.3g".format(maxV)}", left, top-8f, text)
    }
}
