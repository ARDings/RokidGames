package com.rokidgames.headpong

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager

/**
 * Hostet die GameHostView und reicht zwei Eingabe-Pfade an sie weiter:
 *
 *   1. **Touchpad** (rechter Bügel) — Sprite übersetzt Gesten in KeyEvents.
 *      Mapping ist 4-Richtungs-explizit, damit jede Phase eindeutig reagiert:
 *
 *        Swipe ↑   → KEYCODE_DPAD_UP    → onUp()      (Menu: vorherige Auswahl)
 *        Swipe ↓   → KEYCODE_DPAD_DOWN  → onDown()    (Menu: nächste Auswahl)
 *        Swipe ←   → KEYCODE_DPAD_LEFT  → onLeft()    (Snake: turn-left, Asteroid: bias-left)
 *        Swipe →   → KEYCODE_DPAD_RIGHT → onRight()   (Snake: turn-right, Asteroid: bias-right)
 *        Tap       → KEYCODE_DPAD_CENTER/ENTER → onPrimary()  (start, recenter)
 *        Doppel    → KEYCODE_BACK              → onBack()     (zurück ins Menü)
 *
 *   2. **IMU** — TYPE_GAME_ROTATION_VECTOR. Yaw + Pitch werden live an die View
 *      gepushed; Jumper nutzt nur Yaw, Asteroid nutzt beide.
 */
class MainActivity : Activity(), SensorEventListener {

    private lateinit var view: GameHostView
    private lateinit var sm: SensorManager

    private val mat = FloatArray(9)
    private val rot = FloatArray(3)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Display nicht in Standby gehen lassen, solange wir im Vordergrund sind.
        // Greift automatisch nur bei sichtbarer Activity, kein Wakelock-Permission nötig.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = GameHostView(this)
        setContentView(view)
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    override fun onResume() {
        super.onResume()
        sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        sm.unregisterListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        SoundEngine.shutdown()
        super.onDestroy()
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(mat, e.values)
            SensorManager.getOrientation(mat, rot)
            view.headYaw   = rot[0]
            view.headPitch = rot[1]
            // Komplette Rotation als Quaternion-Snapshot für ThreeDofGame.
            // copyOf() weil Android e.values zwischen Events recycelt.
            view.rotationVector = e.values.copyOf()
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP    -> { view.onUp();    true }
            KeyEvent.KEYCODE_DPAD_DOWN  -> { view.onDown();  true }
            KeyEvent.KEYCODE_DPAD_LEFT  -> { view.onLeft();  true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { view.onRight(); true }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_SPACE -> { view.onPrimary(); true }

            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_ESCAPE -> {
                if (view.onBack()) true else super.onKeyDown(keyCode, event)
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }
}
