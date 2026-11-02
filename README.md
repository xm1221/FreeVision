# FreeVision

**A fourth-person camera for Minecraft: your own saved perspective, on top of the three vanilla ones.**

Press a key to enter adjust mode and place the camera where you want it; press it again to save.
After that, a second key switches between the vanilla view and yours at any time.

- **Platforms**: Fabric + Forge from one shared `common` codebase (Architectury)
- **Version**: Minecraft `1.20.1`, Java `17`
- **Side**: client only. Nothing is installed on the server, no packets are sent, no gameplay data changes

---

## Why this is not a freecam

A freecam **detaches** from the player: the camera gets its own world coordinate, the player stays
behind, and nothing ties the two together.

FreeVision's camera is always a **rig bolted to the player**:

| | Freecam | FreeVision |
|---|---|---|
| Position | world coordinate; walk away and the camera stays | relative to the player's eyes; follows walking, jumping, swimming, riding |
| Orientation | independent of the player | follows the player's look plus an offset (turning orbits the camera around them) |
| Interaction | often decoupled from the crosshair, or disabled | the crosshair stays on the camera ray, clamped to what the player can actually reach |
| Lifetime | ends when you let go | saved, and can be toggled on and off forever |

What gets saved is **six numbers plus a mode** (yaw, pitch, roll, distance, height, side, absolute
lock). It does not describe *where the camera is*, it describes *how the camera is placed around the
player* - which is exactly why it can follow you.

---

## Controls

Every binding lives in `Options -> Controls -> FreeVision`, and every hint in the mod prints the key
you actually bound, not a hard-coded string.

| Default key | Action |
|---|---|
| `F9` Adjust perspective | press once to enter adjust mode, press again to save |
| `F8` Switch custom perspective | toggle between the vanilla view and the saved one |
| `Backspace` Discard | throw the current adjustment away (Esc is never intercepted) |
| `V` View lock | while adjusting or active, toggle *follows your look* / *view lock* |
| `Arrow keys` Turn | turn the camera without a mouse |
| `,` / `.` Roll | Dutch angle while adjusting |
| `[` / `]` Dolly | push/pull the rig without a scroll wheel (trackpads) |
| `R` Reset | put the camera back at the player's eyes |
| `WASD` | move the rig along the camera's own facing |
| `Space` / `Shift` | raise / lower the rig |
| `Ctrl` (sprint key) | fine adjustment, 1/5 speed |
| Mouse wheel | dolly along the view axis |

> Rebinding is done in the vanilla controls screen, and the on-screen text follows your binding.
> Esc still opens the pause menu while adjusting, so you can always reach options or quit.

---

## Workflow

1. **Press `F9`** - adjust mode. The player is frozen; the mouse and movement keys drive the camera,
   and the panel shows the numbers.
2. **Place the camera** - mouse or arrow keys to turn, `WASD` to move, wheel or `[`/`]` to dolly,
   `,`/`.` to roll, `R` to reset.
3. **Press `V` if you want a locked shot** - see below.
4. **Press `F9` again** - saved, and immediately in use (press `Backspace` instead to walk away from it).
5. From then on **`F8`** flips between the vanilla view and yours.

Pressing `F8` before anything is saved does nothing except tell you to press `F9`.

---

## View lock (absolute orientation)

By default the perspective **follows your look**: turn your head and the camera orbits, so the
crosshair always points where you aim. That suits normal play, but for a fixed shot - a side-on
cutaway, a fixed angle looking down at yourself - you want the camera to stop tracking your head.

Press `V` to toggle. The picture does not jump: it rewrites the camera orientation from
"relative to your look" to "a world direction", and from then on the camera keeps that world
orientation while following your position.

- the panel's *mode* row shows `follows your look` or `view lock`
- the lock is saved with the perspective; pressing `V` on an active view also takes effect
  immediately and is written back to the config
- picking still casts from the camera, and any hit beyond your own arm length is ignored, so there is
  never an "interact through the wall" shortcut

---

## State machine

| State | Input | Result |
|---|---|---|
| `VANILLA` | `F9` | -> `SCULPTING`, seeded from the current frame so nothing jumps |
| `VANILLA` | `F8` (perspective saved) | -> `ACTIVE`, flies into the saved view |
| `VANILLA` | `F8` (nothing saved) | hint: press `F9` to adjust one |
| `SCULPTING` | mouse / movement / wheel / roll | edit the six rig numbers |
| `SCULPTING` | `V` | switch follows-look / view lock without moving the picture |
| `SCULPTING` | `F9` or `F8` | save -> `ACTIVE` |
| `SCULPTING` | `Backspace` | discard and return to the previous state |
| `ACTIVE` | `F8` | -> `LEAVING` (220 ms blend) -> `VANILLA` |
| `ACTIVE` | `F9` | -> `SCULPTING`, keep fine-tuning the current view |
| `ACTIVE` | `V` | toggle the lock and write it back |
| any | `Esc` | opens the pause menu normally; the adjustment state and numbers survive |

- While `SCULPTING` the player's input is frozen and vanilla game keys (drop, attack, hotbar...) are
  muted, so nothing fires when you leave adjust mode either.
- Opening a screen pauses the input naturally; closing it resumes without losing the camera.
- Entering and leaving both use the same 220 ms eased blend, seeded from whatever the vanilla camera
  was showing - so taking over from third person, a bed or spectator does not snap.

---

## Config file

`config/freevision.json`, generated automatically, safe to hand-edit:

```json
{
  "settings": {
    "sculptSpeed": 0.22,
    "maxOffset": 16.0,
    "transitionMs": 220,
    "smoothTransitions": true,
    "avoidClipping": true,
    "cameraPicking": true,
    "disableViewBobbing": true,
    "showHud": true,
    "showPanel": true,
    "showToasts": true,
    "playSounds": true,
    "precisionModifier": true
  },
  "pose": { "yawOffset": 0.0, "pitchOffset": -18.0, "roll": 0.0, "distance": 4.5, "height": 1.6, "side": -0.8, "absolute": false }
}
```

| Option | Meaning |
|---|---|
| `sculptSpeed` | blocks moved per client tick while adjusting |
| `maxOffset` | how far the rig may leave the eyes (blocks); keeps poses sane |
| `avoidClipping` | pull the camera out of walls while active (like vanilla third person); off while adjusting so you can place it anywhere |
| `cameraPicking` | cast the crosshair from the camera instead of the player's eyes |
| `transitionMs` | blend duration for entering/leaving; 0 = hard cut |
| `showHud` | master switch for every overlay |
| `showPanel` | the adjusting panel (numbers and key hints) |
| `showToasts` | short status toasts |

Option changes apply on the next launch; the saved perspective (`pose`) is written immediately.

---

## Installation

1. Install **Architectury API** (Fabric also needs **Fabric API**).
2. Drop `freevision-fabric-1.0.0.jar` into Fabric's `mods/`, or `freevision-forge-1.0.0.jar` into
   Forge's `mods/`. The two jars are different - do not mix them up.

The Forge jar declares `displayTest = "IGNORE_ALL_VERSION"`, so a client with the mod can join a
server that does not have it.

---

## Building

```bash
# Gradle itself needs JDK 21+; the mod is compiled to Java 17 bytecode
export JAVA_HOME=/path/to/jdk-21
./gradlew build
# outputs: fabric/build/libs/freevision-fabric-1.0.0.jar
#          forge/build/libs/freevision-forge-1.0.0.jar
```

Development runs: `./gradlew :fabric:runClient` or `:forge:runClient`.

Maintainers: read [HUMAN.md](HUMAN.md) first. Implementation notes: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

---

## Known limitations

- **Independent of, but not additive with, vanilla third person**: while the custom perspective is
  active, FreeVision owns the camera. Pressing `F5` only changes the vanilla camera mode; the picture
  stays on your saved rig until you leave it.
- **The server sees nothing unusual**: no extra movement or interaction packets; the camera is purely
  client side.
- **Reach**: you can aim from the custom camera, but a hit is still limited to the player's own arm
  length, so no server-side distance check is ever tripped.
- **Under view lock the crosshair and the body facing separate**: that is the definition of the mode,
  not a bug - the camera ignores your look, so the crosshair no longer represents where the body faces.
- **Water/lava fog** is computed at the camera position (a through-wall shot sees the fog density at
  the camera's coordinates - vanilla behaviour).
- With another mod that also owns the camera (a freecam, for instance), FreeVision wins.

---

## License

MIT - see [LICENSE](LICENSE).
