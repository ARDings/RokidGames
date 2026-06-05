# Game Collection

https://www.linkedin.com/feed/update/urn:li:activity:7458215418005155841/

A tiny retro-style game collection for the **Rokid Glasses** (RV101 — see-through AI glasses with binocular Micro-LED waveguide displays). Five mini-games rendered as monochrome green pixel art, controlled by the temple touchpad and head movement.

> 📦 Single APK, no Rokid SDK dependency. Just plain Android. `minSdk = 32`.

---

## Games

| Game        | What it is                                                        | Controls                                                    |
|-------------|-------------------------------------------------------------------|-------------------------------------------------------------|
| **Jumper**  | Endless platform hopper. Platforms teleport after each landing.   | Touchpad swipe up/down                                      |
| **Snake**   | Classic snake with speed-up per food.                             | Touchpad swipe left = turn left, right = turn right         |
| **Asteroid**| Dodge asteroids flying out of a 3D wireframe tunnel.              | Head yaw/pitch moves the ship; swipes nudge the head bias   |
| **3D Pong** | Real 3DOF pong inside a world-anchored box; CPU opponent at the back.| Head movement aims the view-locked paddle                |
| **3DOF**    | Demo: a wireframe cube that stays put in space while you turn your head. | Tap = re-anchor                                       |
| **Dino**    | Endless runner — jump over cacti, speed increases with distance.    | Tap = jump, double-tap = back to menu                    |

All games render to a low-res 80 × 120 pixel bitmap, then upscaled (nearest-neighbor) to the full display — gives you crisp pixel art that suits the monochrome green Micro-LED panel. Synth-generated sound effects on every meaningful event (no audio assets in the APK).

---

## Build & install

Requires Android Studio with AGP 8.5+, a Rokid Glasses 5-pin magnetic data cable, and ADB enabled on the glasses (via the **Hi Rokid** companion app: Settings → Developer → ADB Debugging).

```powershell
.\gradlew assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.rokidgames.headpong/.MainActivity
```

The app shows up in the Sprite Launcher grid as **Game Collection** with a diskette icon.

---

## Touchpad mapping (per phase)

The Rokid Sprite-Launcher translates the temple-touchpad gestures into standard Android key events. Mapping is explicit per phase to avoid double-binding:

| Phase    | UP        | DOWN      | LEFT          | RIGHT          | TAP                 | Double-Tap |
|----------|-----------|-----------|---------------|----------------|---------------------|------------|
| Menu     | prev      | next      | –             | –              | start selected      | –          |
| Jumper   | move right| move left | –             | –              | –                   | back       |
| Snake    | –         | –         | turn left     | turn right     | –                   | back       |
| Asteroid | –         | –         | nudge right¹  | nudge left¹    | re-center           | back       |
| 3D Pong  | –         | –         | –             | –              | re-anchor           | back       |
| 3DOF     | –         | –         | –             | –              | re-anchor           | back       |
| Dino     | –         | –         | –             | –              | jump                | back       |

¹ Asteroid swipes are inverted on purpose — it feels right that "swipe in the direction you want the ship to go" works opposite to how the head would tilt.

---

## Project structure

```
app/src/main/java/com/rokidgames/headpong/
├── MainActivity.kt        Activity host; routes IMU + KeyEvents to GameHostView
├── GameHostView.kt        Phase machine, menu, common rendering pipeline
├── SoundEngine.kt         PCM synth (tones, sweeps, noise) — no audio files
├── JumperGame.kt          Endless platform hopper
├── SnakeGame.kt           Snake with turn-relative steering
├── AsteroidGame.kt        3D wireframe-tunnel asteroid dodger
├── ThreePongGame.kt       3DOF-anchored pong with CPU opponent
├── ThreeDofGame.kt        World-anchored cube demo
└── DinoGame.kt            Chrome Dino-style endless runner
```

Each game implements `init()` / `update(dt, ...)` / `draw(canvas, best)` and exposes `score` + `gameOver`. The host owns the phase state machine, the pixel bitmap, the rendering paints, and per-game best-scores.

---

## Hardware target

- **Rokid Glasses RV101** (binocular monochrome green Micro-LED + diffractive waveguide, 30° FOV, 480 × 640 per eye, 6-axis IMU)
- YodaOS-Sprite (Android 12 / API 32, ARM64)
- Convergence distance is **infinity** — both eyes see the same image. No real stereo depth; all 3D effects are perspective-only.

---

## Documentation

Detailed hardware specs, SDK research notes, and design rationale (in German, written during prototyping) live in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). Useful if you want to understand:
- Why we don't use the Rokid CXR SDKs
- How 3DOF tracking is built on top of `TYPE_GAME_ROTATION_VECTOR`
- The 6-axis IMU limitations and what they mean for "fake 6DOF"
- The sideloading procedure for the special 5-pin magnetic cable

---

## License

MIT.
