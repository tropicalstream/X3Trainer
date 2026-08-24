package com.x3trainer.wear

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
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The whole watch UI: a pulse, a cadence, and one line saying whether the
 * phone is listening.
 *
 * It is deliberately almost nothing. The wearer's attention belongs on the
 * glasses — this screen exists so that when the chain is not working, it is
 * obvious WHERE it is not working, which on a three-device relay is most of
 * the debugging.
 */
class MainActivity : Activity() {

    private lateinit var hrView: TextView
    private lateinit var cadenceView: TextView
    private lateinit var statusView: TextView
    private val ui = Handler(Looper.getMainLooper())

    private val needed: Array<String>
        get() = buildList {
            add(Manifest.permission.BODY_SENSORS)
            add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            setPadding(24, 24, 24, 24)
        }
        hrView = TextView(this).apply {
            setTextColor(0xFFFF2D9B.toInt()); textSize = 40f; gravity = Gravity.CENTER
        }
        cadenceView = TextView(this).apply {
            setTextColor(0xFF26C6FF.toInt()); textSize = 15f; gravity = Gravity.CENTER
        }
        statusView = TextView(this).apply {
            setTextColor(0xFFBFD8E6.toInt()); textSize = 12f; gravity = Gravity.CENTER
        }
        root.addView(hrView); root.addView(cadenceView); root.addView(statusView)
        setContentView(root)

        val missing = needed.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        // Ask first, start second. A service that starts without BODY_SENSORS
        // registers a listener that never fires, which looks exactly like a
        // watch that is not being worn.
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1) else startBridge()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        val sensorsOk = permissions.zip(grantResults.toTypedArray())
            .none { it.first == Manifest.permission.BODY_SENSORS &&
                    it.second != PackageManager.PERMISSION_GRANTED }
        if (sensorsOk) startBridge()
        else WearState.set(status = "Heart-rate permission denied")
        render()
    }

    private fun startBridge() {
        startForegroundService(Intent(this, BroadcastService::class.java))
    }

    override fun onResume() {
        super.onResume()
        WearState.onChange = { ui.post { render() } }
        render()
    }

    override fun onPause() {
        WearState.onChange = null
        super.onPause()
    }

    private fun render() {
        hrView.text = if (WearState.hr > 0) "${WearState.hr}" else "--"
        cadenceView.text = if (WearState.cadence > 0) "${WearState.cadence} spm" else "-- spm"
        statusView.text = WearState.status
        statusView.setTextColor(if (WearState.linked) 0xFF50F29B.toInt() else 0xFFBFD8E6.toInt())
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
