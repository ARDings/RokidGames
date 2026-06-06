# Ventylator

A Bluetooth LE HUD for the **Storz & Bickel Venty** vaporizer on **Rokid AI Glasses**. Displays live temperature, battery, heater mode, session timer, and session counter — always in your field of view. Green phosphor monochrome, no distractions.

> 📦 Single APK, no Rokid SDK dependency. Just plain Android. `minSdk = 32`.

---

## Features

| Feature | Detail |
|---------|--------|
| **BLE connection** | Auto-connects to Venty via Bluetooth LE |
| **Temperature** | Live target temp with ±5°C swipe adjustment |
| **Heater control** | Tap to toggle on/off |
| **Battery** | Live percentage with charging indicator |
| **Session timer** | Tracks current session duration (reset on standby) |
| **Session counter** | ST = sessions today, TS = total sessions (persisted) |

---

## Controls

| Gesture | Action |
|---------|--------|
| **Swipe down** | Temperature +5°C |
| **Swipe up** | Temperature -5°C |
| **Tap** | Toggle heater on/off |
| **Double-tap** | Exit app |

---

## Build & install

Requires Android Studio, a Rokid Glasses 5-pin magnetic data cable, and ADB enabled on the glasses (Hi Rokid app → Settings → Developer → ADB Debugging).

```powershell
.\gradlew assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.rokidgames.headpong/.MainActivity
```

The app shows up in the Sprite Launcher as **Ventylator**.

---

## Project structure

```
app/src/main/java/com/rokidgames/headpong/
├── MainActivity.kt         Activity host — BLE permissions + key routing
├── GameHostView.kt         Venty HUD host — low-res render pipeline
├── VentyBleService.kt      BLE manager — scan, connect, GATT, protocol parsing
├── VentyHud.kt             HUD rendering — green phosphor text list
└── SoundEngine.kt          PCM synth — click/confirm sounds, no audio files
```

---

## Venty BLE protocol

Reverse-engineered from [reactive-volcano-app](https://github.com/firsttris/reactive-volcano-app) and [storz-rs](https://github.com/flakesonnix/storz-rs).

- **Service**: `00000000-5354-4f52-5a26-4249434b454c`
- **Control characteristic**: `00000001-5354-4f52-5a26-4249434b454c` (Write + Notify)
- **Device name prefix**: `S&B VY`
- Commands use 20-byte buffers with CMD byte at position 0
- State notifications arrive via the control characteristic (CMD 0x01)

---

## Hardware target

- **Rokid Glasses RV101** (binocular monochrome green Micro-LED + diffractive waveguide, 30° FOV, 480×640 per eye, 6-axis IMU)
- YodaOS-Sprite (Android 12 / API 32, ARM64)

---

## Links

- 🌐 [xrchris.com](https://xrchris.com)
- ☕ [Ko-fi](https://ko-fi.com/xrchris)
- 🍩 [Buy Me a Coffee](https://buymeacoffee.com/xrchris)

---

## License

MIT.
