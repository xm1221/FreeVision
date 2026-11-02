# HUMAN.md - maintainer's handbook

**English** · [简体中文](HUMAN.zh-CN.md)

This document is for whoever **maintains** the mod: how to build it, where things live, what breaks
when you touch it, and how to debug it. Player-facing docs are in [README.md](README.md); the
implementation notes are in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

---

## 1. Quick start

```powershell
# Gradle needs a JDK 21+ runtime (the mod itself is still compiled to Java 17)
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'   # adjust to your machine
.\gradlew.bat build

# outputs
#   fabric\build\libs\freevision-fabric-1.0.0.jar
#   forge\build\libs\freevision-forge-1.0.0.jar

# development clients
.\gradlew.bat :fabric:runClient
.\gradlew.bat :forge:runClient
```

`runClient` reaches the title screen in about 40 seconds. **If the log contains no `MixinApplyError`,
no `InjectionError` and no `could not find`, every injection point resolved.** That grep is the first
thing to run on a startup crash:

```powershell
Select-String -Path fabric\run\logs\latest.log -Pattern "Mixin|Injection|freevision"
```

Minecraft 1.20.1 and its dependencies are already in the Gradle cache, so a rebuild takes ~10 s.
Do **not** run Gradle on JDK 17: Loom 1.17 requires a build JVM of 21 or newer.

---

## 2. Directory map

```
common/src/main/java/cn/xm1221/freevision/
├── camera/
│   ├── CameraPose.java          <- rig data + all the maths (the one file that must be thought about)
│   ├── VisionState.java         <- the four states
│   ├── VisionController.java    <- state machine, key handling, hints, sounds
│   ├── VisionCameraBridge.java  <- resolves state into a pose, once per frame
│   └── VisionPicker.java        <- re-casts the crosshair from the camera
├── config/
│   ├── VisionConfig.java        <- reads/writes config/freevision.json, sanitises on load
│   └── VisionSettings.java      <- user options + sanitize()
├── client/
│   ├── VisionKeybinds.java      <- every KeyMapping
│   ├── VisionHud.java           <- adjusting panel + toasts
│   └── FreeVisionClient.java    <- bootstrap (called by the platform modules)
└── mixin/                       <- the injection points, see below
```

The platform modules hold two files each: `fabric/.../FreeVisionFabric.java` and
`forge/.../FreeVisionForge(+Client).java`. They **only register key mappings**; everything else is in
`common`.

---

## 3. Injection points: read before changing code

| Mixin | Target | Purpose | What breaks if it dies |
|---|---|---|---|
| `CameraMixin` | `Camera#setup` TAIL | write pose, roll, `detached` | camera does nothing |
| `CameraEyeHeightAccessor` | `Camera.eyeHeight*` | read the smoothed eye height | view snaps when crouching |
| `EntityTurnMixin` | `Entity#turn` HEAD | mouse turns the camera | turning rotates the body |
| `KeyboardInputMixin` | `KeyboardInput#tick` TAIL | freeze player movement | the player walks while you adjust |
| `MinecraftMixin` | `Minecraft#tick` HEAD | drive the state machine | nothing works at all |
| `MinecraftMixin` | `Minecraft#handleKeybinds` HEAD | mute gameplay keys | stray attacks / dropped items |
| `GameRendererMixin` | `pick` / `renderItemInHand` / `bobView` | crosshair / hand / view bob | misaligned outline, floating hand |
| `GuiOverlayMixin` | `Gui#renderSpyglassOverlay` / `#renderTextureOverlay` | first-person screen effects (Fabric only) | pumpkin/scope overlays bleed in |
| `MouseHandlerMixin` | `MouseHandler#onScroll` | wheel dolly | the GUI eats the scroll |

Three hard rules:

1. **`defaultRequire = 1`**: if any target is missing, the game **crashes at startup**. That is
   deliberate - a loud crash beats a silent no-op. Re-check every target with `javap` when moving to a
   new Minecraft version.
2. **Never use `@Redirect` (nor any MixinExtras annotation).** MixinExtras 0.5.4 crashes the game on
   any `@Redirect`; see the pitfalls table below. Everything here uses `@Inject` + `@At`.
3. **Never take Esc.** Esc stays in `KeyboardHandler` so the player can always open the pause menu and
   rebind - an earlier version swallowed it and trapped players inside adjust mode.

### Checking an API the right way

The loom cache on this machine has the mojmap merged jar; `javap` beats reading wiki pages:

```powershell
$jar = Get-ChildItem "$env:USERPROFILE\.gradle\caches\fabric-loom\minecraftMaven" -Recurse -Filter "minecraft-merged-1.20.1-*.jar" | Select-Object -First 1
& "C:\Program Files\Java\jdk-17\bin\javap.exe" -p -classpath $jar.FullName net.minecraft.client.Camera
```

For method bodies, use `.tools/` (vineflower plus a decompiled tree) - it is git-ignored. **Never**
commit decompiled output.

---

## 4. Common maintenance tasks

### Change a default binding
`client/VisionKeybinds.java`, `create()`. Change the `GLFW.GLFW_KEY_*` constant. The controls screen
picks the entry up automatically and every hint shows the new name (all hints go through
`getTranslatedKeyMessage()`; **never** hard-code "F8" in a lang file).

### Add a tunable option
1. add the field with a default in `config/VisionSettings.java`;
2. add the range check to `sanitize()` (**required** - the file is hand-editable);
3. read it through `VisionConfig.get().settings.xxx`.

### Change the saved perspective
`VisionConfig` has a single `pose` field (`null` = nothing saved yet):

- `pose()` / `hasPose()` to read;
- `storePose(pose)` to write (it copies, so nobody shares a mutable instance);
- `clearPose()` to wipe.

The old build stored a `presets` list plus `activeSlot`; `sanitise()` migrates the selected entry into
`pose` on load and drops the legacy fields. **Do not bring the slot list back**: that design defaulted
`next_slot` to F9, which later collided with the adjust key on F9 - `KeyMapping`'s key lookup keeps
only the last entry, so F9 cycled slots and the editor never opened.

### Change the rig maths (carefully)
Everything is in `CameraPose`:

- `cameraYaw / cameraPitch / position`: turn the six player-relative numbers into a world pose;
- `fromWorld`: fold any vanilla camera back into rig space (the blend endpoints depend on it);
- `convertMode`: relative mode <-> view lock; the picture must not move at the switch;
- `lerp`: **both endpoints must share a mode**; `VisionCameraBridge` converts first.

After changing anything here, run the standalone maths check (no game needed):

```powershell
& "C:\Program Files\Java\jdk-17\bin\java.exe" tools\RigMathCheck.java
# expected: ALL RIG MATH CHECKS PASSED
```

It runs 200k random `world -> rig -> world` round trips (error should be ~1e-11), checks that the
orbit radius stays constant, and checks shortest-arc yaw interpolation.

### Change HUD text
`common/src/main/resources/assets/freevision/lang/{en_us,zh_cn}.json`.

**1.20.1 only understands `%s` and `%%`** - `%d` or `%.1f` will not crash, but the whole line silently
degrades to displaying the raw template. Format numbers in Java with `String.format(Locale.ROOT, ...)`
and only pass finished strings as arguments.

---

## 5. Pitfalls and conventions (all of these were hit for real)

| Pitfall | Notes |
|---|---|
| The Gradle JVM must be 21+ | Loom 1.17's unpick dependency requires it; output is still compiled with `options.release = 17` |
| No refmap needed | Loom's remapper rewrites the names inside the annotations to intermediary/SRG at packaging time; verify with `javap -v` after changes |
| `@Accessor` field names are remapped too | do not assume strings survive - see `CameraEyeHeightAccessor` |
| Forge wants the event bus by **constructor injection** | `FMLJavaModLoadingContext.get()` is `forRemoval` since 47.4. `FMLModContainer.constructMod` first looks for `getDeclaredConstructor(FMLJavaModLoadingContext.class)` and only then falls back to the no-arg constructor - hence `public FreeVisionForge(FMLJavaModLoadingContext context)` |
| `pack.mcmeta` belongs in the **platform modules** | with it in `common`, the Forge dev launch logs `Missing metadata in pack mod:freevision` because the dev mod file cannot see common's resource directory. One copy per platform keeps dev and production quiet, with a single copy in each jar |
| Shift / Ctrl may be a `ToggleKeyMapping` | with "toggle crouch/sprint" on, `isDown()` stays true forever; read the physical key with `physicallyDown()` |
| Two actions on one key = the earlier one **silently dies** | `KeyMapping`'s lookup is rebuilt from `Options.keyMappings` and the later entry wins. `FreeVisionClient` now logs a WARN on duplicates at startup |
| **Forge's GUI is not `Gui`** | Forge replaces `Minecraft.gui` with `net.minecraftforge.client.gui.overlay.ForgeGui`, which **overrides `render` and never calls `Gui#render`**. Anything injected into `Gui#render` is dead code on Forge: no error, no crash, nothing drawn. The HUD must go through Architectury's `ClientGuiEvent.RENDER_HUD` (Forge: `RenderGuiEvent.Post`, Fabric: its own hook) |
| Debugging "it does not draw" starts with "is the hook even called?" | a one-shot `LOG.info` at the top of `VisionHud.render` is what narrowed the Forge HUD bug from "render error" to "hook never ran" |
| **Do not use `@Redirect`** (or any MixinExtras annotation) | MixinExtras **0.5.4** does `(AnnotationNode) Annotations.getValue(redirect, "at")` in `FactoryRedirectWrapperMixinTransformer`, but javac stores the array-typed element as a **list** (`at=[@At(...)]`), so it throws `ClassCastException` while transforming the target class and the game **refuses to start**. 0.5.5 fixed it, which is why the dev client (Loader ships 0.5.5) never reproduced it. All injections use `@Inject` + `@At`; both jars were verified to contain zero references to `spongepowered/asm/mixin/injection/Redirect` and `com/llamalad7/mixinextras` |
| Scan the annotations before a release | unzip `cn/xm1221/freevision/mixin/*.class` from the jar and text-search for `injection/Redirect` and `com/llamalad7`; both must be empty. This class of bug cannot be caught by running your own instance |
| Do not use `isDown()` to detect a mode toggle | use `consumeClick()` with a debounce, and handle **at most one press per tick** |
| `detached = false` when the rig is within 0.14 blocks of the eyes | otherwise the player sees the inside of their own head; hand hiding is tied to the same flag so "no hand and no body" cannot happen |
| The config is untrusted input | `sanitise()` pulls 1e6 / NaN back into range on load; otherwise the per-frame clip ray walks millions of blocks and stalls the client |
| Reach must never exceed vanilla | `VisionPicker` drops hits farther from the eyes than the player's arm and falls back to the vanilla result |
| Hint durations | 3.5 s for confirmations, 6 s for "do this next"; never pass 0 (0 is treated as "use the default") |

---

## 6. Release checklist

- [ ] `.\gradlew.bat build` passes and both jars are produced
- [ ] `java tools\RigMathCheck.java` passes
- [ ] `:fabric:runClient` and `:forge:runClient` each reach the title screen with no `MixinApplyError`
      / `InjectionError`
- [ ] in a world: `F9` adjust -> `F9` save -> `F8` toggle -> `V` lock -> discard key -> `Esc` opens the
      pause menu
- [ ] rebind one key and confirm the hints show the new name
- [ ] the key table in `README.md` matches `VisionKeybinds`
- [ ] version bumped in `gradle.properties` (`mod_version`)
- [ ] commit: `git add -A && git commit -m "..."`

---

## 7. Deliberate trade-offs (not bugs - do not "fix" them casually)

- **F8/F9 are function keys**: Macs and some laptops need Fn, but the original request named them;
  rebinding is one click away.
- **All gameplay keys are muted while adjusting**, including inventory and chat. In exchange nothing
  can be attacked or dropped by accident. Exit with the save key, the discard key, or Esc.
- **The crosshair separates from the body facing under view lock**: that is the mode.
- **Picking follows the camera ray**: the farther the rig, the closer the reachable area is to vanilla;
  anything past the player's arm is dropped.
- **The config is not hot-reloaded**: restart to apply (key rebinds apply immediately - that is
  vanilla behaviour).

---

## 8. Branches and commits

- Mainline is `master`; one feature per commit; commit messages in English, imperative mood.
- `git status` must be clean before committing; `.tools/`, `build/` and `run/` are already ignored.
