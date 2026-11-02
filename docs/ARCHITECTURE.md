# 架构与 API

本文说明 FreeVision 的内部结构、每帧的数据流，以及如果你想在此基础上继续扩展时可以依赖的接口。

---

## 0. 分层

```
common/  ────────────────────────────────────────────────┐
  camera/   CameraPose  VisionState  VisionController      │ 纯逻辑：数学、状态机、混合
            VisionCameraBridge  VisionPicker               │ 不碰渲染，可脱离游戏推演
  config/   VisionConfig  VisionSettings                   │ 持久化（Gson → config/freevision.json）
  client/   VisionKeybinds  VisionHud  FreeVisionClient     │ 键位、覆盖层、启动
  mixin/    8 个注入点                                      │ 唯一允许接触引擎内部的地方
fabric/   FreeVisionFabric        （ClientModInitializer） │ 只管注册键位
forge/    FreeVisionForge(-Client)（DistExecutor 隔离）     │ 只管注册键位
```

设计原则：**引擎内部只在一个方向被触碰**——mixin 只做「取输入」和「写相机」两件事，
所有判断、数学与状态都留在 `camera/` 里。这样即使某个注入点需要为新版本重写，
业务逻辑一行都不用动。

---

## 1. `CameraPose` —— 视角的数据模型

```java
public final class CameraPose {
    public float yawOffset;    // 叠加到玩家视角偏航上的角度
    public float pitchOffset;  // 叠加到玩家视角俯仰上的角度
    public float roll;         // 荷兰角
    public double distance;    // 沿相机自身视线轴、向后的距离
    public double height;      // 世界竖直方向的偏移
    public double side;        // 沿相机左向量的偏移
}
```

### 坐标系

设相机偏航 `Y = 玩家视角偏航 + yawOffset`，则

```
back  = ( sin Y, 0, -cos Y )   // 相机视线的水平反向
left  = ( cos Y, 0,  sin Y )   // 相机的左向量
eye   = 玩家插值后的眼睛位置

cameraPos = eye + back*distance + left*side + (0, height, 0)
cameraYaw = Y
cameraPitch = clamp(玩家视角俯仰 + pitchOffset, ±89.9°)
```

三个后果，也是整个设计的核心：

1. **偏航随玩家走**。玩家在 `ACTIVE` 状态转头，`Y` 同步变化，机位绕玩家旋转——
   这就是第三人称相机的手感，而不是镜头被钉死在世界某处。
2. **俯仰是叠加的**，所以准星方向始终等于玩家的瞄准方向；你在高角度看自己时，
   抬头低头看到的依然是「自己在瞄的地方」。
3. **所有分量都被 `clamp()` 关在 `maxOffset` 的盒子里**，再怎么摆也摆不出一个离谱的机位。

### `fromWorld(...)`：把任意原版相机折算成 rig

```java
CameraPose.fromWorld(eye, cameraPos, viewYaw, viewPitch, cameraYaw, cameraPitch)
```

它做的事是把一个**世界坐标的相机**投影回上面那套基向量上：

```
dx = cameraPos - eye
distance = dx · back(cameraYaw)
side     = dx · left(cameraYaw)
height   = dx.y
yawOffset   = wrapDegrees(cameraYaw - viewYaw)
pitchOffset = cameraPitch - viewPitch
```

用途有两个，都很关键：

- **进入调整时**：如果玩家正处在原版第三人称（F5 后镜头在身后 4 格），按住键的瞬间机位
  会**无缝接管**那个位置，而不是「啪」地跳回眼睛。躺床、旁观者模式同理。
- **离开视角时**：把这一帧原版算出来的相机折算成目标，混合动画的终点因此永远精确等于原版画面。

`lerp()` 对偏航用最短弧（`wrapDegrees` 后再插值），所以从 350° 到 10° 只走 20°，不会反向甩一圈。

---

## 2. `VisionState` + `VisionController` —— 状态机

```java
VANILLA →(按住)→ SCULPTING →(松开)→ DRAFT →(短按，保存)→ LEAVING → VANILLA
                                        ↑                    │
                                        └──(按住，接着改)      ↓ (短按)
                                              ACTIVE ←────────┘
                                                └──(短按)→ LEAVING → VANILLA
```

`LEAVING` 是一个真实状态而不是标志位：它让「已经松手、但还在 220ms 的退场动画里」的这段时间
也走同一套求值路径，于是退场结束时相机**恰好**等于原版相机，不需要任何对齐补帧。

### 短按 / 按住

同一个键必须同时承担「切换」和「调整」两种意图，所以按下时**什么都不做**，先计时：

- 按住超过 `holdThresholdMs`（默认 220ms）→ 从当前画面起手，进入 `SCULPTING`；
- 在此之前松手 → 算一次**短按**：`VANILLA`→启用存档、`ACTIVE`→退出、`DRAFT`→保存。

这样「短按」没有任何延迟感，而「按住」的判定延迟只有 220ms。

### 输入的三层接管

| 输入 | 接管点 | 效果 |
|---|---|---|
| 鼠标 | `Entity#turn` HEAD，`cancel()` | 原版已经把灵敏度、反转 Y、平滑镜头、开镜减速都算完了，直接把结果喂给 `handleMouseTurn`，机位转动的手感和玩家自己的设置完全一致；同时 `cancel` 掉，身体不转 |
| 键盘移动 | `KeyboardInput#tick` TAIL | 把 `up/down/left/right/jumping/shiftKeyDown` 清零，玩家原地不动；原始按键状态仍可从 `Options.keyUp` 读到，正好给机位移动用 |
| 游戏键位 | `Minecraft#handleKeybinds` HEAD，`cancel()` | 调整期间不丢物品、不出手、不开背包、不切 F5；同时 `consumeClick()` 把积压的点击排掉，松手后不会突然触发 |

`Esc` 在 `SCULPTING` / `DRAFT` 里被 `KeyboardHandler#keyPress` 截下来当作「放弃」；
其它状态一律放行，照常打开暂停菜单。

打开任意界面时，`KeyMapping` 会被原版 `releaseAll()` 清空，控制器据此把会话落到 `DRAFT`——
已经摆好的机位不会丢。

---

## 3. `VisionCameraBridge` —— 每帧一次的解算

`Camera#setup` 每帧跑一次，注入点在其 TAIL。此刻：

- `camera.getPosition()` / `getYRot()` / `getXRot()` 是**原版刚算出来的**值（第三人称的后拉、
  跑动视角、躺床都还在）；
- 我们随后把这些值整个覆盖成自己的解算结果。

```java
public static CameraPose computePose(Entity entity, Camera camera, float partialTick)
```

流程：

1. 状态是 `VANILLA` → 返回 `null`，一个字节都不碰。
2. 若有一次尚未落地的「从当前画面开始」的过渡（`pendingVanillaSeed`），
   用刚才那组原版值折算成 rig，写进 `blendFrom`（必要时同时初始化 `sculptPose`）。
3. `LEAVING` 的目标是**这一帧的原版相机**；其它状态的目标是 `targetPose()`。
4. `progress ≥ 1` 直接返回目标；`LEAVING` 则返回 `null` 并把状态收成 `VANILLA`。
5. 否则 `lerp(blendFrom, target, smoothstep(progress))`。

**混合发生在 rig 空间而不是世界空间**，所以动画过程中玩家走动、转身都不会让镜头「追不上」：
两端都是相对玩家的量，插值结果天然跟随。

`smoothstep(t) = t²(3-2t)` 两端导数为 0，进出不会有速度突变。

进度用**墙上时钟**（`System.nanoTime()`）而不是帧计数，卡帧或一帧里多次取值都不会让动画提速。

### 遮挡裁剪

`computePosition()` 在 `ACTIVE` / `LEAVING`（且 `avoidClipping` 开启）时，从眼睛到机位做一次
`ClipContext.Block.VISUAL` 射线，撞到方块就把机位收到命中点前 0.15 格——和原版第三人称一样的礼貌。
`SCULPTING` / `DRAFT` 不裁剪，方便你把机位摆进任何地方先看清楚。

---

## 4. 渲染侧的三件事

### 4.1 让玩家自己出现在画面里

`LevelRenderer` 只在这一个条件下才渲染「当前相机实体」：

```java
entity != camera.getEntity() || camera.isDetached() || ...
```

相机实体仍然是玩家，所以机位一旦离开头部就置 `camera.detached = true`。这不是 hack：语义上它确实
「离开了头部」。机位贴着双眼时（不到 0.14 格）保持 `false`，画面就退化成正常的第一人称，
而不是让你看到自己脑袋的内壁。

### 4.2 藏掉贴着眼睛的手

第一人称的手模、窒息遮罩、望远镜边框都挂在 `CameraType.FIRST_PERSON` 这个分支上。
`GameRenderer#renderItemInHand` 整段 `cancel()`（同时带走一帧的投影矩阵重置，无害），
`Gui#render` 里对 `Options#getCameraType()` 做一次 `@Redirect` 返回 `THIRD_PERSON_BACK`，
于是南瓜脸、望远镜遮罩也一并消失。

**我们不改 `Options#getCameraType()` 的真实值**：玩家的 F5 设置保持原样，退出视角后立刻恢复，
也不会和别的模组抢状态。

### 4.3 准星

`GameRenderer#pick` 的 TAIL 里重做一次拾取（`VisionPicker`）：

- 从相机位置沿相机视线打 4.5 格（创造/旁观按原版规则到 5 / 6 格）；
- 方块用 `Block.OUTLINE`，实体用 `ProjectileUtil.getEntityHitResult`，取更近的那个；
- **安全边界**：命中点如果离玩家眼睛超过原版手长，直接放弃、保留原版结果。
  这样永远不会向服务端发送一个超出原版距离的交互，也不会因为机位远而「隔空挖矿」。

---

## 5. 注入点总表

| Mixin | 目标 | 位置 | 作用 |
|---|---|---|---|
| `CameraMixin` | `Camera#setup` | TAIL | 写入机位与翻滚，`detached = true` |
| `EntityTurnMixin` | `Entity#turn` | HEAD/cancel | 鼠标转镜头，身体不动 |
| `KeyboardInputMixin` | `KeyboardInput#tick` | TAIL | 冻结玩家移动 |
| `MinecraftMixin` | `Minecraft#tick` | HEAD | 驱动状态机 |
| `MinecraftMixin` | `Minecraft#handleKeybinds` | HEAD/cancel | 静音游戏键位 |
| `GameRendererMixin` | `GameRenderer#pick` | TAIL | 相机准星 |
| `GameRendererMixin` | `GameRenderer#renderItemInHand` | HEAD/cancel | 藏掉第一人称手/遮罩 |
| `GameRendererMixin` | `GameRenderer#bobView` | HEAD/cancel | 关掉跑动视角抖动 |
| `GuiMixin` | `Gui#render` | TAIL + Redirect | HUD 与第一人称装饰 |
| `MouseHandlerMixin` | `MouseHandler#onScroll` | HEAD/cancel | 滚轮推拉机位 |
| `KeyboardHandlerMixin` | `KeyboardHandler#keyPress` | HEAD/cancel | Esc = 放弃 |

翻滚（roll）在 `Camera#setRotation`（只认 yaw/pitch）之后，绕相机局部 Z 轴再旋转四元数一次，
并重新推导 `forwards/up/left`。不用改原版字段结构。

---

## 6. 可以怎么往下扩展

- **更多保存位**：`VisionSettings.maxSlots` 已支持多槽，`VisionConfig.presets` 是普通列表；
  想做成轮盘，读 `presets` 自己画一个即可。
- **固定俯仰的过场机位**：把 `CameraPose#cameraPitch()` 里的 `+ 玩家俯仰` 去掉就是「只跟偏航」的
  电影机位；不影响准星逻辑之外的任何东西。
- **跟随缓动**：`CameraPose#position()` 加时间常数做指数平滑，就能得到软跟随；
  但注意它会被 `VisionPicker` 的射线段共享，记得同步。
- **新状态（例如轨道环绕、自动推轨）**：加进 `VisionState` 与 `VisionController#targetPose()`，
  渲染侧一行都不用改。

---

## 7. 平台差异备忘

- **不需要 refmap**：Loom 的 tiny-remapper mixin 扩展会在打包时把注解里的方法名直接改写成
  目标平台名字（Fabric → `method_xxxxx`，Forge → `m_xxxxx_`）。已核对两个 jar 的字节码。
- **Fabric**：`fabric.mod.json` 声明 `environment: client`，专用服务器不会加载。
- **Forge**：1.20.1 的 `@Mod` 没有 `dist` 参数，客户端部分通过 `DistExecutor.unsafeRunWhenOn`
  隔离在 `FreeVisionForgeClient` 里，专用服务器永远不会链接到客户端类。
