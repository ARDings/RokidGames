package com.rokidgames.headpong

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Venty BLE HUD — single-activity host.
 *
 * Input:
 *   Swipe UP/DOWN → temp ±1°C
 *   Tap           → toggle heater
 *   Double-tap    → reconnect BLE
 */
class MainActivity : Activity() {

    private lateinit var view: GameHostView

    companion object {
        private const val REQ_BLE = 42
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = GameHostView(this)
        setContentView(view)
        requestBlePermissions()
    }

    private fun requestBlePermissions() {
        val missing = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED)
            missing += Manifest.permission.BLUETOOTH_SCAN
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED)
            missing += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)
                missing += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_BLE)
        }
    }

    override fun onDestroy() {
        SoundEngine.shutdown()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP    -> { view.onUp();    true }
            KeyEvent.KEYCODE_DPAD_DOWN  -> { view.onDown();  true }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_SPACE -> { view.onPrimary(); true }

            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_ESCAPE -> {
                view.onBack(); true
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }
}
