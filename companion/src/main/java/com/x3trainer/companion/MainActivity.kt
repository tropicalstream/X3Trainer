package com.x3trainer.companion

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var watch: TextView
    private lateinit var glasses: TextView
    private lateinit var values: TextView
    private lateinit var detail: TextView

    private val refresh = object : Runnable {
        override fun run() {
            val s = BridgeState.snapshot
            watch.text = "WATCH\n${s.watchStatus}"
            glasses.text = "X3 PRO\n${s.glassesStatus}"
            values.text = if (s.heartRate > 0) {
                "${s.heartRate} bpm    ${s.cadence} spm"
            } else {
                "-- bpm    -- spm"
            }
            val age = if (s.lastSampleAt > 0L) {
                ((android.os.SystemClock.uptimeMillis() - s.lastSampleAt) / 1000L).coerceAtLeast(0L)
            } else null
            detail.text = buildString {
                append("Heart-rate broadcaster → this phone → RayNeo X3 Pro")
                if (age != null) append("\nLatest sensor sample: ${age}s ago")
                append("\n\nPixel Watch: Settings › Connectivity › Connected Fitness › Connect. Galaxy Watch: run X3Trainer Link. Any standard BLE chest strap works too. Data stays on your devices.")
            }
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(4, 10, 16)
        setContentView(buildUi())
        ensureReady()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun granted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Bluetooth is the only thing this app cannot work without: it is how the
     * pulse arrives and how the glasses are fed.
     */
    private fun bluetoothReady() =
        if (Build.VERSION.SDK_INT >= 31)
            granted(Manifest.permission.BLUETOOTH_SCAN) &&
                granted(Manifest.permission.BLUETOOTH_CONNECT)
        else granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun ensureReady() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                if (!granted(Manifest.permission.BLUETOOTH_SCAN))
                    add(Manifest.permission.BLUETOOTH_SCAN)
                if (!granted(Manifest.permission.BLUETOOTH_CONNECT))
                    add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            // Location earns its place on every API level now, not just as
            // the pre-31 stand-in for BLUETOOTH_SCAN: GPS is where pace comes
            // from once the watch stops supplying it.
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION))
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 29 &&
                !granted(Manifest.permission.ACTIVITY_RECOGNITION)
            ) add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= 33 &&
                !granted(Manifest.permission.POST_NOTIFICATIONS)
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // ASK FOR EVERYTHING, GATE ON ALMOST NOTHING. Cadence, pace and the
        // notification are enrichments — declining them should cost the
        // wearer those readings, not the heart rate that is the point of the
        // app. Gating the whole bridge on the full set is also how a denial
        // becomes a loop: every resume asks again and nothing ever starts.
        if (needed.isNotEmpty() && !asked) {
            asked = true
            requestPermissions(needed.toTypedArray(), 82)
        }
        if (bluetoothReady()) startBridge()
    }

    /** One prompt per visit; Android decides whether it is shown at all. */
    private var asked = false

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code != 82) return
        if (bluetoothReady()) startBridge()
    }

    private fun startBridge() {
        val intent = Intent(this, BridgeService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun buildUi(): LinearLayout {
        fun text(size: Float, color: Int, gravity: Int = Gravity.START) = TextView(this).apply {
            textSize = size
            setTextColor(color)
            this.gravity = gravity
            setPadding(0, 14, 0, 14)
        }

        val mint = Color.rgb(94, 235, 198)
        val muted = Color.rgb(147, 170, 184)
        val panel = Color.rgb(10, 23, 32)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 42, 48, 34)
            setBackgroundColor(Color.rgb(4, 10, 16))
            gravity = Gravity.CENTER_HORIZONTAL

            addView(text(29f, Color.WHITE, Gravity.CENTER).apply {
                text = "X3TRAINER BRIDGE"
            }, LinearLayout.LayoutParams(-1, -2))
            addView(text(13f, muted, Gravity.CENTER).apply {
                text = "REAL-TIME WATCH TELEMETRY"
            }, LinearLayout.LayoutParams(-1, -2))

            watch = text(18f, mint, Gravity.CENTER).apply {
                setBackgroundColor(panel)
                text = "WATCH\nStarting…"
            }
            addView(watch, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 28, 0, 10) })

            glasses = text(18f, mint, Gravity.CENTER).apply {
                setBackgroundColor(panel)
                text = "X3 PRO\nStarting…"
            }
            addView(glasses, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 22) })

            values = text(32f, Color.WHITE, Gravity.CENTER)
            addView(values, LinearLayout.LayoutParams(-1, -2))

            detail = text(14f, muted, Gravity.CENTER)
            addView(detail, LinearLayout.LayoutParams(-1, 0, 1f))

            addView(Button(context).apply {
                text = "RESTART BRIDGE"
                setOnClickListener {
                    stopService(Intent(context, BridgeService::class.java))
                    handler.postDelayed({ startBridge() }, 500L)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
}
