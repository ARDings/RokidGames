# RokidGames – HeadPong 3D

Erstes Mini-Game für die **neuen Rokid Glasses** (see-through AI-Brille mit binokularen Micro-LED Wellenleiter-Displays, 49 g, OEM-ID **RV101** – nicht die "AR Lite" / "Max" Birdbath-Brillen). Steuerung per Kopfbewegung, Rendering auf den Brillen-Displays, optionale Phone-Bridge via CXR-S.

Dieses README ist **Single Source of Truth** – alles was wir brauchen ist hier, damit wir nicht ständig Online-Docs durchforsten müssen.

---

## 1. Zielgerät: Rokid Glasses (see-through AI)

### 1.1 Hardware (offizielle Rokid-Specs)

| Eigenschaft | Wert |
|---|---|
| Modell-Variante | **RV101** (Rokid Glasses, see-through AI) |
| Optics | **Micro-LED + Glass Diffractive Waveguide** |
| Light Engine | **Binokular, monochrom grün** |
| Auflösung pro Auge | **480 × 640** (physisch portrait → bei `ROTATION_LEFT` als 640 × 480 landscape sichtbar) |
| FOV | **30°** |
| Helligkeit | bis zu **1500 nits** |
| Convergence Distance | **Infinity** (gleiches Bild auf beiden Augen → Tiefe nur über Perspektive, nicht Stereo-Disparität) |
| SoC | **Qualcomm AR1** + Co-Prozessor **NXP RT600** für Voice/Wake-Word |
| RAM / ROM | 2 GB / 32 GB |
| Konnektivität | Wi-Fi 6, BT 5.3 |
| Kamera | 12 MP Sony IMX681, 3024×4032, F 2.25, FOV D:109° |
| Mikrofone | 4× direktionales Array |
| Lautsprecher | 2× high-fidelity super-linear |
| Eingabe | **Function Button** + **Touch Panel** (1×) |
| Sensoren | **6-axis IMU** (Accel + Gyro, kein Magnetometer), Proximity, Wearing Detection |
| Charging | 5 V / 1 A über 5-Pin-Magnet-Kontakte |
| Maße | **143 × 44 × 160.5 mm** |
| Operating Temp | 0 °C – 35 °C |
| Wasserschutz | IPX4 |
| Akku | 210 mAh |

### 1.2 Software-Plattform

| Eigenschaft | Wert |
|---|---|
| OS | **YodaOS-Sprite**, basierend auf **Android 12 (API 32)** |
| Build-Fingerprint | `Rokid/glasses/glasses:12/SKQ1.240613.001/...:user/release-keys` |
| Min. SDK für Apps | **API 28** (Android 9) |
| Primäre ABI | **arm64-v8a** |
| Grafik | OpenGL ES 3.2, Adreno (über Snapdragon AR1) |
| Default Rotation | `ROTATION_LEFT` (Surface ist 90° gedreht) |
| Refresh-Raten | 90 / 120 / 144 / 180 Hz (thermal-throttled) |
| IMU | InvenSense ICM-4x6xx, **6-axis** (Accel + Gyro) über I3C |
| Head-Tracking | **3-DoF** Rotation (kein Positions-Tracking, kein absoluter Heading wegen fehlendem Magnetometer) |
| Eingabe | Touch Panel (Tap / Doppel-Tap / Swipe) + Function Button (`KeyEvent`) |

### 1.3 Was das fürs Game heißt

- **Binokular, aber Convergence-Distance = Infinity.** Beide Augen sehen das gleiche Bild im "Unendlich"-Fokus. Echte Stereo-Disparität ist nicht vorgesehen – Tiefenwahrnehmung kommt **ausschließlich aus perspektivischer Projektion**. Genau das Pong-Tunnel-Konzept aus dem Referenzbild.
- **Monochrom grün.** Alle Assets in Grünstufen. Highlights = hellgrün, Background = schwarz, Wireframes = mittelgrün. RGB-Rendering wird vom Compositor auf den Grünkanal gemappt, sauberer ist es Assets gleich monochrom zu erzeugen.
- **30° FOV.** Eng – die Tunnel-Geometrie passt perfekt, weil alles um den zentralen Fluchtpunkt rotiert.
- **480 × 640 physisch (= 640 × 480 nach `ROTATION_LEFT`).** Manifest auf `landscape` zwingen oder Achsen sauber drehen.
- **6-axis IMU = nur Accel + Gyro, kein Magnetometer.** Heißt: kein absoluter Heading-Reference. `TYPE_GAME_ROTATION_VECTOR` ist hier die *richtige* Wahl, weil es ohne Magnet-Sensor auskommt. `TYPE_ROTATION_VECTOR` würde teilweise undefiniert/instabil reagieren.
- **3-DoF Head-Tracking.** Reicht für Pong (Yaw/Pitch steuern Paddle).
- **Function Button als Failsafe.** Spielstart, Pause, Restart per Hardware-Button → kommt als normales Android `KeyEvent` rein, einfach im `onKeyDown` abfangen.
- **AR1.** Brillen-SoC, kleine GPU. 60 FPS @ 640×480 monochrom Wireframes ist trivial.

---

## 2. SDK-Landschaft – was macht was

Rokid hat eine **CXR-Suite (Connected XR)** mit drei SDKs. Wichtig zu verstehen: **keiner dieser SDKs ist ein "Display-SDK"** – die Brille ist Android und das Display wird über ganz normales Android-Rendering (View / SurfaceView / OpenGL ES / Vulkan) angesprochen. Die CXR-SDKs handhaben **Kommunikation und Service-Bindings**, nicht Pixel.

| SDK | Zweck | Maven-Artefakt | Brauchen wir? |
|---|---|---|---|
| **CXR-M** | Companion-App auf Phone (Android 9+ / iOS) – pairt + spricht mit Brille | `com.rokid.cxr:client-m:1.0.8` | nur falls wir später eine Phone-App bauen |
| **CXR-S** | On-device App auf der Brille; **Bridge zum Phone** über ARTC + Caps-Messaging; Connection-Status-Listener | `com.rokid.cxr:cxr-service-bridge:1.0-SNAPSHOT` | **ja – als sauberer Sprite-Lifecycle-Hook** |
| **CXR-L** | Standalone-App, **ersetzt** die Default-Rokid-AI-App via AIDL `IMediaStreamService` | `com.rokid.cxr:client-l:0.0.1` | **nein** – wir wollen die System-AI nicht ersetzen |

### 2.1 Was CXR-S **kann** und was **nicht**

CXR-S (`cxr-service-bridge`) ist ein schmales Service-Bridge-SDK. Es macht genau drei Dinge:

1. **Connection-Status zur Phone-Companion-App melden** (`StatusListener`):
   - `onConnected(name, type)` – type: `1=Android, 2=iOS, 3=Unknown`
   - `onDisconnected()`
   - `onARTCStatus(health, reset)` – health = 0.0–1.0 = Erfolgsquote der ARTC-Frames
2. **Messages vom Phone empfangen** (`subscribe(name, MsgCallback)` bzw. `MsgReplyCallback`)
3. **Messages zum Phone senden** (`sendMessage(name, Caps)` ggf. mit Binary-Payload)

Das war's. Es enthält **keine** Display-, Render-, OpenGL-, Sensor- oder Input-APIs. Display-Rendering und Head-Tracking nutzen wir über die normalen Android-Frameworks – das ist **nicht** ein Workaround, sondern der vorgesehene Weg auf YodaOS-Sprite.

### 2.2 Warum wir CXR-S trotzdem reinholen

Auch ohne Phone-Companion lohnt sich der Import:

- Die App ist damit "Sprite-konform" und kann später ohne Refactor mit einer Phone-App reden (Highscore-Sync, Remote-Config, "Brille-zu-Phone Notify").
- `StatusListener` ist nützlich fürs Debugging – wir sehen sofort, ob die Brille gepairt ist.
- Setup ist trivial (1 Maven-Repo + 1 Dependency) und es zieht keinen großen Footprint.

### 2.3 CXR-S Setup

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven { url = uri("https://maven.rokid.com/repository/maven-public/") }
        mavenCentral()
    }
}
```

```kotlin
// app/build.gradle.kts
android {
    defaultConfig {
        minSdk = 28
        ndk { abiFilters += "arm64-v8a" }   // Brille ist arm64-only
    }
}
dependencies {
    implementation("com.rokid.cxr:cxr-service-bridge:1.0-20250519.061355-45")
}
```

### 2.4 CXR-S Cheat-Sheet (Code, damit wir nicht nachschlagen müssen)

```kotlin
private val cxrBridge = CXRServiceBridge()

private val statusListener = object : CXRServiceBridge.StatusListener {
    override fun onConnected(name: String, type: Int) {
        Log.i("HeadPong", "Phone connected: $name (type=$type)")
    }
    override fun onDisconnected() { Log.i("HeadPong", "Phone disconnected") }
    override fun onARTCStatus(health: Float, reset: Boolean) {
        Log.v("HeadPong", "ARTC health=$health reset=$reset")
    }
}

private val msgCallback = object : CXRServiceBridge.MsgReplyCallback {
    override fun onReceive(name: String, args: Caps, value: ByteArray?, reply: Reply?) {
        // z.B. "set_difficulty" vom Phone empfangen
        val replyArgs = Caps().apply { write("ack") }
        reply?.end(replyArgs)
    }
}

fun initCxr() {
    cxrBridge.setStatusListener(statusListener)
    cxrBridge.subscribe("headpong.cmd", msgCallback)         // <-- vom Phone hören
}

fun pushHighscore(score: Int) {
    val args = Caps().apply { write(score) }                  // Caps = linked-list serializer
    cxrBridge.sendMessage("headpong.highscore", args)         // <-- ans Phone schicken
}
```

**Caps-Returncodes** (alle `subscribe`/`sendMessage`): `0 = ok`, `-1 = bad params`, `-2 = duplicate subscribe`, `-3 = internal error`.

---

## 3. Optional: System-Services über AIDL nutzen

Die Brille hat einen `RokidSpriteAssistServer`, an den wir per AIDL binden können. Nicht nötig für Pong, aber gut zu wissen:

| Service | Was es macht |
|---|---|
| `MasterAssistService` | Foto/Video, Audio, QR-Scan, Bluetooth-Phone-Bridge, TTS, Scene-Management |
| `TtsService` | On-device Text-to-Speech – z. B. `"GAME OVER"` aussprechen |
| `SystemFuncService` | Lautstärke, Helligkeit, Akkustand, Notifications |
| `SpriteMediaService` | Kamera + AR-Mixed-Reality-Recording |
| `MultiSpProvider` | Cross-process SharedPreferences (für globale Settings) |

Bind via Standard-Android `bindService` + AIDL-Interface (Interfaces müssen aus den decompiled APKs extrahiert werden – siehe [buildwithfenna/rokid-docs](https://github.com/buildwithfenna/rokid-docs) wenn wir das brauchen).

---

## 4. Dev-Environment Setup

### 4.1 Hardware

- **Spezial-Datenkabel zwingend nötig.** Die Brille hat einen 5-Pin-Magnet-Ladeport. Das mitgelieferte Standard-Ladekabel überträgt **nur Strom**, keine Daten. Wir brauchen ein **5-Pin Magnet → USB-A/C Datenkabel** (Rokid Developer-Programm, Taobao, Shopee – Suchbegriff "Rokid Glasses ADB cable").
- Windows-Treiber: Standard Google USB-Driver / Universal ADB Driver.

### 4.2 Software

1. Android Studio (aktuelle Version), Kotlin 2.x, AGP 8.x.
2. Android SDK Platform-Tools (`adb`).
3. **"Hi Rokid" Companion-App** auf dem Phone:
   - Android Play: `com.rokid.sprite.global.aiapp` ([Play Store](https://play.google.com/store/apps/details?id=com.rokid.sprite.global.aiapp))
   - iOS App Store: "Hi Rokid - Rokid Glasses"
   - In China: `com.rokid.sprite.aiapp`
4. In der Hi-Rokid-App: **Brille → Einstellungen → Developer → ADB-Debugging aktivieren**.

### 4.3 ADB-Verbindung & Deployment

```powershell
# Brille per Magnet-Datenkabel ans Notebook stecken
adb devices                                                    # Brille muss in der Liste auftauchen
adb shell getprop ro.build.fingerprint                         # sollte "Rokid/glasses/..." enthalten
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.rokidgames.headpong/.MainActivity
adb logcat -s HeadPong:V AndroidRuntime:E
```

Nach `adb install` taucht die App im **Sprite-Launcher-Grid** auf. Steuerung per Touch-Slider:

| Geste | Funktion |
|---|---|
| Tap | Enter / App öffnen |
| Doppel-Tap | Back / App schließen |
| Swipe vor / zurück | Auswahl im Grid wechseln |

**Alternativ ohne Kabel:** [Miniontoby/RokidApkUploader](https://github.com/Miniontoby/RokidApkUploader) – Android-App, die per CXR-M über Wi-Fi APKs hochlädt.

---

## 5. Head-Tracking via Standard Android Sensor-API

Für 3-DoF Kopfsteuerung nehmen wir `Sensor.TYPE_GAME_ROTATION_VECTOR` (kein Magnetometer-Drift, ideal für Spiele). Quaternion → Euler → Paddle-Position.

```kotlin
class HeadTracker(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val rotMatrix = FloatArray(9)
    private val orientation = FloatArray(3)   // [yaw, pitch, roll] in rad

    var yaw = 0f; var pitch = 0f; var roll = 0f
        private set

    fun start() = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    fun stop()  = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotMatrix, e.values)
        SensorManager.getOrientation(rotMatrix, orientation)
        yaw = orientation[0]; pitch = orientation[1]; roll = orientation[2]
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}
```

**Achsen-Hinweis:** Wegen `ROTATION_LEFT` ist das Achsen-Mapping evtl. zu tauschen. Beim ersten Test mit Logcat verifizieren – ggf. yaw/pitch tauschen oder negieren.

**Kalibrierung:** Beim App-Start aktuelle yaw/pitch als Origin merken und subtrahieren – sonst hängt das Paddle dauerhaft schief, abhängig davon wie der User gerade sitzt.

---

## 6. Display-Rendering – pragmatisch

Display-Rendering läuft auf Sprite über das normale Android-Surface (SurfaceFlinger sitzt unter der Haube). Eine `SurfaceView` oder `GLSurfaceView` in einer Activity ist alles, was wir brauchen.

**Variante A – Canvas/SurfaceView (für den Prototyp empfohlen):**
- Fake-3D durch perspektivische Projektion + Z-Sortierung
- Schnell zu implementieren, läuft butterweich auf AR1
- Passt 1:1 zum Wireframe-Look des Referenzbildes
- Ideal für monochromen Output (`Paint` mit grünen Farbwerten)

**Variante B – OpenGL ES 3.2:**
- Echte 3D-Pipeline; sinnvoll wenn wir später Glow-Shader, Tunnel-Verzerrung oder Tron-Postprocessing wollen
- Mehrwert sehr begrenzt für ein erstes Pong

**Empfehlung:** Mit Canvas-Variante starten, OpenGL-Pfad nur wenn nötig.

**Manifest-Pflicht-Settings:**
```xml
<activity
    android:name=".MainActivity"
    android:screenOrientation="landscape"
    android:configChanges="orientation|screenSize|keyboardHidden"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

Das Standard-`LAUNCHER`-Intent reicht damit der Sprite-Launcher die App im Grid anzeigt – er hört auf `PACKAGE_ADDED` und scannt das `LAUNCHER`-Filter.

**Monochrome-Pipeline:** Da das Display nur Grün darstellt, am besten:
- Hintergrund `Color.BLACK`
- Linien/Sprites in `Color.argb(255, 0, 200, 0)` Stufen
- Glow per Additive-Blend (`PorterDuff.Mode.ADD`) auf grünen Halos
- Antialiasing an, damit dünne Wireframe-Linien bei 640×480 nicht flimmern

---

## 7. Game-Konzept "HeadPong 3D"

Look & Feel exakt wie auf dem Referenzbild: schwarzer Hintergrund, **grüner Wireframe-Tunnel** mit konzentrischen Rechtecken (Fluchtpunkt-Perspektive), **leuchtende Kugel** als Ball, **blau-eingerahmtes** Spieler-Paddle (im Monochrom-Display erscheint Blau natürlich auch grün – wir markieren das Spieler-Paddle stattdessen mit dickerer Outline / Pulsieren), **rotes Gegner-Paddle** am hinteren Tunnelende. HUD: "BONUS: xxxx" rechts unten, Statusmeldungen ("Curve Bonus!") in der Mitte.

### Mechanik

| Element | Verhalten |
|---|---|
| Ball | Bewegt sich entlang Z (Tiefe), reflektiert an Tunnelwänden, Paddles und Endwand |
| Spieler-Paddle | Position = (yaw, pitch) gemappt auf (-1..1, -1..1) im Tunnel-Querschnitt |
| Gegner-Paddle | Lerpt zur Ball-XY-Position mit konstanter Speed (skalierbar = Difficulty) |
| Tunnel | Statische Wireframe-Frames bei Z = 0, 5, 10, 15, … – Tiefen-Linien dazwischen |
| Score | +100 pro Reflexion, +Bonus bei "Curve" (Spin-Reflex – Roll-Wert ≠ 0 beim Treffer) |

### Architektur-Skizze

```
app/src/main/java/com/rokidgames/headpong/
├── MainActivity.kt          # SurfaceView host, fullscreen/immersive, lifecycle
├── GameView.kt              # SurfaceView + Game-Loop (Choreographer-driven)
├── input/
│   └── HeadTracker.kt       # Wrapper um SensorManager (siehe oben)
├── bridge/
│   └── CxrBridge.kt         # Wrapper um CXRServiceBridge (StatusListener + sendMessage)
├── engine/
│   ├── World.kt             # Ball, Paddles, Tunnel, Update-Loop
│   ├── Ball.kt
│   ├── Paddle.kt
│   └── Projection.kt        # 3D-Punkt → 2D-Screen via Fluchtpunkt-Projektion
└── render/
    ├── TunnelRenderer.kt    # Wireframe-Tunnel
    ├── EntityRenderer.kt    # Ball + Paddles
    └── HudRenderer.kt       # Score, Bonus-Text
```

Zielwerte: 60 FPS @ 640×480 (landscape nach `ROTATION_LEFT`), Input-to-Display-Latenz < 50 ms (Sensor-Delay GAME ≈ 20 ms + 1 Frame Render).

---

## 8. Was wir bewusst **nicht** tun

- **Kein eigenes Stereo-Rendering.** Sprite-Compositor liefert ein Frame an beide Wellenleiter; wir rendern monoskop in 2D-Pseudo-3D.
- **Kein Farbe.** Display ist monochrom grün – alles in Grünstufen.
- **Kein 6-DoF.** Hardware kann nur Rotation.
- **Kein CXR-L.** Wir wollen die Default-AI-App **nicht** ersetzen.
- **Kein Vendor-Sensor-Hack.** Wir bleiben in der normalen Android Sensor-API – das hält die App auf allen Sprite-Firmware-Versionen lauffähig.

---

## 9. Quellen / Weiterführend

- Offizielles Rokid AR Developer-Portal (SPA – nur im Browser nutzbar): https://ar.rokid.com/sdk?lang=en
- YodaOS-Sprite Übersicht: https://ar.rokid.com/sprite?lang=en
- Offizielle Brillen-Specs: https://global.rokid.com/products/rokid-glasses
- Rokid Developer Forum: https://forum.rokid.com / https://developer-forum.rokid.com
- Community-Doku (sehr ausführlich, reverse-engineered): https://github.com/buildwithfenna/rokid-docs
- Sideload-Tutorial (chin., Magnetkabel-Bezugsquellen): https://vocus.cc/article/697adabafd89780001026b46
- WiFi-APK-Uploader via CXR-M (alternativ ohne Kabel): https://github.com/Miniontoby/RokidApkUploader
- Hi Rokid App (Android): https://play.google.com/store/apps/details?id=com.rokid.sprite.global.aiapp
- Maven Repo Rokid: https://maven.rokid.com/repository/maven-public/

---

## 10. Quickstart (Build & Deploy)

Projekt-Struktur ist gesetzt; Game-Code (Engine + Renderer + HeadTracker + CXR-Bridge) liegt unter [app/src/main/java/com/rokidgames/headpong/](app/src/main/java/com/rokidgames/headpong/).

### Einmalig

1. **Android Studio öffnen** → "Open" → Repo-Root.
2. Gradle-Sync abwarten. Beim ersten Sync zieht Android Studio den Gradle-Wrapper automatisch und resolved die `cxr-service-bridge`-Dependency vom Rokid-Maven.
3. Falls das Rokid-Maven gerade nicht erreichbar ist: `CxrBridge` ist reflection-basiert und no-op't ohne die Lib — die App startet trotzdem. Notfalls die Dependency in [app/build.gradle.kts](app/build.gradle.kts) auskommentieren.

### Spezial-Magnetkabel + Hi Rokid App + ADB aktivieren

Siehe Abschnitt 4. Sanity-Check:
```powershell
adb devices
adb shell getprop ro.build.fingerprint    # sollte "Rokid/glasses/..." enthalten
```

### Build & Install

```powershell
.\gradlew assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.rokidgames.headpong/.MainActivity
adb logcat -s HeadPong:V HeadPong/CxrBridge:V AndroidRuntime:E
```

App taucht im Sprite-Launcher als "HeadPong" auf. Tap = Start / Pause / Restart.

### Architektur (was wo liegt)

| Datei | Aufgabe |
|---|---|
| [MainActivity.kt](app/src/main/java/com/rokidgames/headpong/MainActivity.kt) | Activity-Lifecycle, immersive Fullscreen, KeyEvent → Game-Action |
| [GameView.kt](app/src/main/java/com/rokidgames/headpong/GameView.kt) | `SurfaceView` + Choreographer-Loop, dispatcht Update + Draw |
| [input/HeadTracker.kt](app/src/main/java/com/rokidgames/headpong/input/HeadTracker.kt) | `TYPE_GAME_ROTATION_VECTOR`, Auto-Kalibrierung, normalisierte yaw/pitch |
| [bridge/CxrBridge.kt](app/src/main/java/com/rokidgames/headpong/bridge/CxrBridge.kt) | Reflection-Wrapper um `CXRServiceBridge` (no-op falls Lib fehlt) |
| [engine/Projection.kt](app/src/main/java/com/rokidgames/headpong/engine/Projection.kt) | Pinhole-3D→2D, aspect-aware |
| [engine/Ball.kt](app/src/main/java/com/rokidgames/headpong/engine/Ball.kt), [Paddle.kt](app/src/main/java/com/rokidgames/headpong/engine/Paddle.kt) | Game-Entities |
| [engine/World.kt](app/src/main/java/com/rokidgames/headpong/engine/World.kt) | Physik, Kollisionen, Score, Lives, Curve-Bonus |
| [render/TunnelRenderer.kt](app/src/main/java/com/rokidgames/headpong/render/TunnelRenderer.kt) | Wireframe-Tunnel mit Tiefen-Dimming |
| [render/EntityRenderer.kt](app/src/main/java/com/rokidgames/headpong/render/EntityRenderer.kt) | Ball + Paddles, Z-sortiert |
| [render/HudRenderer.kt](app/src/main/java/com/rokidgames/headpong/render/HudRenderer.kt) | Lives, Score, Bonus-Texte |
| [render/Palette.kt](app/src/main/java/com/rokidgames/headpong/render/Palette.kt) | Grünstufen für die monochrome Pipeline |

### Was als nächstes ansteht

- Auf der echten Brille testen → Achsen-Mapping in `HeadTracker` ggf. drehen/negieren.
- Sound (TTS via `RokidSpriteAssistServer.TtsService` für "GAME OVER" / Score-Calls).
- Difficulty-Stufen (Enemy-Speed, Ball-Speed-Acceleration).
- Phone-Companion-App mit CXR-M für Highscore-Sync.
