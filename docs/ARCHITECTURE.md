# Architecture and API

**English** · [简体中文](ARCHITECTURE.zh-CN.md)

This document covers the internal structure, the per-frame data flow, and the interfaces you can rely
on if you want to build on top of FreeVision.

---

## 0. Layering

```
common/  ────────────────────────────────────────────────┐
  camera/   CameraPose  VisionState  VisionController      │ pure logic: maths, state, blending;
            VisionCameraBridge  VisionPicker               │ no rendering, can be reasoned about
  config/   VisionConfig  VisionSettings                   │ persistence (Gson -> config/freevision.json)
  client/   VisionKeybinds  VisionHud  FreeVisionClient     │ bindings, overlay, bootstrap
  mixin/    injection points + one accessor                 │ the only place that touches the engine
fabric/   FreeVisionFabric        (ClientModInitializer)   │ only registers key mappings
forge/    FreeVisionForge(-Client) (DistExecutor isolation)│ only registers key mappings
```

Design rule: **the engine is touched in exactly two ways** - mixins take input out and write the
camera back. All decisions, maths and state live in `camera/`. If an injection point has to be
rewritten for a new Minecraft version, no business logic moves.

---

## 1. `CameraPose` - the data model of a perspective

```java
public final class CameraPose {
    public float yawOffset;    // relative mode: added to the player's view yaw; absolute mode: world yaw
    public float pitchOffset;  // same
    public float roll;         // dutch angle
    public double distance;    // backwards along the camera's own view axis
    public double height;      // world-vertical offset
    public double side;        // along the camera's left vector
    public boolean absolute;   // false = tracks the player's look; true = view lock (world orientation)
}
```

### Coordinate frame

With camera yaw `Y` (relative mode: `player view yaw + yawOffset`; absolute mode: just `yawOffset`):

```
back  = ( sin Y, 0, -cos Y )   // opposite the camera's horizontal view direction
left  = ( cos Y, 0,  sin Y )   // the camera's left vector
eye   = the player's interpolated eye position

cameraPos   = eye + back*distance + left*side + (0, height, 0)
cameraYaw   = Y
cameraPitch = relative: clamp(player pitch + pitchOffset) | absolute: clamp(pitchOffset)
```

Three consequences, and they are the whole design:

1. **Yaw follows the player** (relative mode). Turning in `ACTIVE` changes `Y`, so the rig orbits the
   player - third-person camera feel, not a camera pinned to a world position.
2. **Pitch is additive** (relative mode), so the crosshair direction always equals the player's aim.
3. **Every component is clamped into `maxOffset`**, so no pose can be absurd.

### Two rotation modes and `convertMode`

With `absolute = true`, `yawOffset`/`pitchOffset` become **world angles**: the camera stops tracking
the look direction and only follows the position. `convertMode(targetAbsolute, viewYaw, viewPitch)`
rewrites the numbers at the **current frame**, so the picture does not move at the switch - only the
tracking rule changes afterwards. `VisionController#toggleLock` calls it.

### `fromWorld(...)`: folding any vanilla camera into a rig

```java
CameraPose.fromWorld(eye, cameraPos, viewYaw, viewPitch, cameraYaw, cameraPitch, absolute)
```

Projects a world-space camera back onto the basis above (`distance = dx·back`, `side = dx·left`,
`height = dx.y`). Two uses:

- **entering adjust mode / activating**: the player may be in vanilla third person, in bed or in
  spectator, and the takeover must not snap;
- **leaving**: the blend's destination must be exactly the vanilla frame.

`lerp()` uses shortest-arc yaw, so 350° -> 10° travels 20°. **Both endpoints must share a mode**; the
camera bridge converts the origin first.

---

## 2. `VisionState` + `VisionController` - the state machine

```java
VANILLA --(F9 adjust)--> SCULPTING --(F9/F8 save)--> ACTIVE --(F8 toggle)--> LEAVING --> VANILLA
                              └──────(discard key)──────> back to the previous state
```

**Adjusting is an explicit mode, not a hold**: press once to enter, press again to save, discard key to
throw it away. The player can read the panel, go change settings, or alt-tab without losing the pose.
`LEAVING` is a real state rather than a flag, so "already switched away but still blending" goes
through the same evaluation path and needs no alignment correction at the end.

### Three input takeovers

| Input | Hook | Effect |
|---|---|---|
| Mouse | `Entity#turn` HEAD, `cancel()` | vanilla already applied sensitivity / invert / smoothing / scope slowdown; the finished deltas go to `handleMouseTurn`, and cancelling keeps the body still |
| Movement keys | `KeyboardInput#tick` TAIL | zeroes `up/down/left/right/jumping/shiftKeyDown`; the raw keys are still read from `Options.keyUp` for the rig |
| Gameplay keys | `Minecraft#handleKeybinds` HEAD, `cancel()` | no dropping, no attacking, no F5 while adjusting; every click in `Options.keyMappings` is drained so nothing fires later |

**Esc is never intercepted**, so the pause menu always opens (a hard requirement from the
accessibility review: the previous version used Esc as "discard" and left players unable to reach
options or quit). Discarding has its own key. While a screen is open the editor simply pauses
(vanilla releases the `KeyMapping`s) and resumes when it closes; `VisionController#tick` returns early
whenever `screen != null`, so nothing changes state behind a GUI.

### Bindings

All native `KeyMapping`s, rebindable in the controls screen. Every hint resolves the **player's actual
key** through `KeyMapping#getTranslatedKeyMessage()`; "F8" is never hard-coded.

| Field | Default | Meaning |
|---|---|---|
| `vision` | F8 | toggle the saved perspective |
| `edit` | F9 | enter adjusting / save |
| `lock` | V | toggle view lock (absolute orientation) |
| `discard` | Backspace | throw the adjustment away |
| `lookUp/Down/Left/Right` | arrows | turn without a mouse |
| `dollyIn/Out` | `[` `]` | push/pull without a wheel |
| `rollLeft/rollRight` | `,` `.` | roll |
| `reset` | R | put the rig back at the eyes |

---

## 3. `VisionCameraBridge` - one solve per frame

`Camera#setup` runs once per frame and the injection sits at its TAIL. At that moment
`camera.getPosition()/getYRot()/getXRot()` hold the **values vanilla just computed**, and we replace
them wholesale.

```java
public static CameraPose computePose(Entity entity, Camera camera, float partialTick, Vec3 eye)
```

Flow:

1. `VANILLA` -> return `null`, touch nothing.
2. `LEAVING` targets **this frame's vanilla camera**; other states target `targetPose()`.
3. If the origin and destination modes differ, `convertMode` the origin first.
4. `progress >= 1` returns the target; for `LEAVING` it returns `null` and collapses the state to
   `VANILLA`.
5. Otherwise `lerp(from, target, smoothstep(progress))` and `recordApplied(pose)` so the next takeover
   is seamless.

**Blending happens in rig space, not world space**, so walking or turning mid-animation cannot make the
camera lag. `smoothstep(t) = t²(3-2t)` has zero derivative at both ends; progress uses wall-clock time,
so a frame spike cannot speed the animation up.

### Occlusion clipping

`computePosition()` casts a `ClipContext.Block.VISUAL` ray from the eyes to the rig in `ACTIVE` /
`LEAVING` (when `avoidClipping` is on) and pulls the camera 0.15 blocks in front of the hit. `SCULPTING`
does not clip, so you can place the camera anywhere while working.

---

## 4. The render side

### 4.1 Letting the player appear on screen

`LevelRenderer` renders "the camera entity" only under this condition:

```java
entity != camera.getEntity() || camera.isDetached() || ...
```

The camera entity is still the player, so once the rig leaves the head we set `camera.detached = true`.
Within 0.14 blocks it stays `false`, which degrades to a normal first-person view instead of showing the
inside of the player's head.

### 4.2 Hiding the hand and the screen effects

`GameRenderer#renderItemInHand` is cancelled outright (it also drops one frame of projection reset,
which is harmless). Pumpkin blur, spyglass frame and the snow/frost outline are cancelled by
`GuiOverlayMixin` at the HEAD of their own methods. Both only act when the camera really left the head.
**`Options` is never mutated**, so the player's F5 setting is untouched.

### 4.3 Crosshair

`GameRenderer#pick` gets a TAIL injection that redoes the pick (`VisionPicker`): a 4.5 block ray from
the camera along its view direction (5/6 in creative/spectator, per vanilla), `Block.OUTLINE` for
blocks and `ProjectileUtil.getEntityHitResult` for entities, whichever is closer.
**Safety boundary**: a hit farther from the player's eyes than vanilla reach is discarded and the
vanilla result is kept - an interaction the server would reject is never sent.

### 4.4 HUD: the one place that deliberately avoids a mixin

The HUD registers on Architectury's `ClientGuiEvent.RENDER_HUD` instead of injecting into `Gui#render`.

This is not laziness, it is Forge: Forge replaces `Minecraft.gui` with
`net.minecraftforge.client.gui.overlay.ForgeGui`, which **overrides `render` and never calls
`Gui#render`**. Anything injected into `Gui#render` is dead code there - no error, no crash, just
nothing drawn, which is the worst kind of bug to find. `ClientGuiEvent.RENDER_HUD` is driven by
`RenderGuiEvent.Post` on Forge and by Architectury's own hook on Fabric, and both hand over a
`GuiGraphics`; it is the only genuinely cross-platform drawing entry point in this project.

`GuiOverlayMixin` uses `@Inject` (**not `@Redirect`**, see below) to suppress the pumpkin/spyglass/snow
overlays on Fabric. Forge draws those through its own overlay system, which is a known platform
difference.

> Why no `@Redirect`: MixinExtras 0.5.4's `FactoryRedirectWrapperMixinTransformer` casts the `at` value
> straight to `AnnotationNode`, while javac stores that array-typed element as a list - the resulting
> `ClassCastException` fails the target class transformation and the game refuses to start. Every
> injection in this project uses `@Inject` + `@At` only.

---

## 5. Injection points

| Mixin | Target | Position | Purpose |
|---|---|---|---|
| `CameraMixin` | `Camera#setup` | TAIL | write the pose and roll, set `detached` |
| `CameraEyeHeightAccessor` | `Camera` fields | - | read vanilla's smoothed eye height |
| `EntityTurnMixin` | `Entity#turn` | HEAD/cancel | mouse turns the camera, not the body |
| `KeyboardInputMixin` | `KeyboardInput#tick` | TAIL | freeze player movement |
| `MinecraftMixin` | `Minecraft#tick` | HEAD | drive the state machine |
| `MinecraftMixin` | `Minecraft#handleKeybinds` | HEAD/cancel | mute gameplay keys |
| `GameRendererMixin` | `GameRenderer#pick` | TAIL | camera-space crosshair |
| `GameRendererMixin` | `GameRenderer#renderItemInHand` | HEAD/cancel | hide the first-person hand |
| `GameRendererMixin` | `GameRenderer#bobView` | HEAD/cancel | drop view bobbing |
| `GuiOverlayMixin` | `Gui#renderSpyglassOverlay` / `#renderTextureOverlay` | HEAD/cancel | first-person screen effects (Fabric only) |
| `MouseHandlerMixin` | `MouseHandler#onScroll` | HEAD/cancel | wheel dolly |

Roll is applied after `Camera#setRotation` (which only knows yaw and pitch) by rotating the quaternion
around the camera's local Z axis and re-deriving `forwards/up/left`.

---

## 6. Extending it

- **More saved perspectives**: `VisionConfig` holds one `pose`; turn it into a list and the state
  machine does not need to change.
- **A fixed-pitch cutaway**: view lock plus a chosen `pitchOffset`, no code needed.
- **Soft follow**: add a time constant in `CameraPose#position()` for exponential smoothing; note that
  `VisionPicker` shares that position, so keep them in sync.
- **A new state**: add it to `VisionState` and `VisionController#targetPose()`; the render side needs
  no changes.

---

## 7. Platform notes

- **No refmap**: Loom's tiny-remapper mixin extension rewrites the names inside the annotations to the
  target platform's names at packaging time (Fabric -> `method_xxxxx` / `field_xxxxx`, Forge ->
  `m_xxxxx_` / `f_xxxxx_`). Verified against both shipped jars.
- **Fabric**: `fabric.mod.json` declares `environment: client`, so a dedicated server never loads it.
- **Forge**: 1.20.1's `@Mod` has no `dist` parameter, so the client half is isolated behind
  `DistExecutor.unsafeRunWhenOn` in `FreeVisionForgeClient`; a dedicated server never links the client
  classes.
