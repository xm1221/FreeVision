# 架构与 API

[English](ARCHITECTURE.md) · **简体中文**

本文说明 FreeVision 的内部结构、每帧的数据流，以及想在此基础上扩展时可以依赖的接口。

---

## 0. 分层

```
common/  ────────────────────────────────────────────────┐
  camera/   CameraPose  VisionState  VisionController      │ 纯逻辑：数学、状态机、混合
            VisionCameraBridge  VisionPicker               │ 不碰渲染，可脱离游戏推演
  config/   VisionConfig  VisionSettings                   │ 持久化（Gson → config/freevision.json）
  client/   VisionKeybinds  VisionHud  FreeVisionClient     │ 键位、覆盖层、启动
  mixin/    注入点 + 1 个 accessor                          │ 唯一允许接触引擎内部的地方
fabric/   FreeVisionFabric        （ClientModInitializer） │ 只管注册键位
forge/    FreeVisionForge(-Client)（DistExecutor 隔离）     │ 只管注册键位
```

设计原则：**引擎内部只从两个方向被触碰**——mixin 只做「取输入」和「写相机」。
所有判断、数学与状态都留在 `camera/`，因此某个注入点需要为新版本重写时，业务逻辑一行都不用动。

---

## 1. `CameraPose` —— 视角的数据模型

```java
public final class CameraPose {
    public float yawOffset;    // 相对模式：叠加到玩家视线偏航；绝对模式：世界偏航
    public float pitchOffset;  // 同上
    public float roll;         // 荷兰角
    public double distance;    // 沿相机自身视线轴、向后的距离
    public double height;      // 世界竖直方向偏移
    public double side;        // 沿相机左向量的偏移
    public boolean absolute;   // false = 跟随玩家视线；true = 镜头锁定（世界朝向）
}
```

### 坐标系

设相机偏航 `Y`（相对模式为 `玩家视线偏航 + yawOffset`，绝对模式直接等于 `yawOffset`）：

```
back  = ( sin Y, 0, -cos Y )   // 相机水平视线的反向
left  = ( cos Y, 0,  sin Y )   // 相机的左向量
eye   = 玩家插值后的眼睛位置

cameraPos   = eye + back*distance + left*side + (0, height, 0)
cameraYaw   = Y
cameraPitch = 相对：clamp(玩家俯仰 + pitchOffset) ｜ 绝对：clamp(pitchOffset)
```

三个后果，也是整个设计的核心：

1. **偏航随玩家走**（相对模式）。在 `ACTIVE` 转头时 `Y` 同步变化，机位绕玩家旋转——
   第三人称手感，而不是镜头被钉死在世界某处。
2. **俯仰是叠加的**（相对模式），所以准星方向始终等于玩家的瞄准方向。
3. **所有分量都被 `clamp()` 关在 `maxOffset` 的盒子里**，摆不出离谱的机位。

### 两种旋转模式与 `convertMode`

`absolute = true` 时 `yawOffset/pitchOffset` 改为表示**世界角度**：镜头不再跟随视线，只跟随位置。
切换由 `convertMode(targetAbsolute, viewYaw, viewPitch)` 完成，它在**当前这一帧**改写数值，
所以切换瞬间画面完全不动，只是此后镜头行为变了。调用方是 `VisionController#toggleLock`。

### `fromWorld(...)`：把任意原版相机折算成 rig

```java
CameraPose.fromWorld(eye, cameraPos, viewYaw, viewPitch, cameraYaw, cameraPitch, absolute)
```

把世界坐标相机投影回上面那套基向量（`distance = dx·back`、`side = dx·left`、`height = dx.y`），两个用途：

- **进入调整 / 启用视角时**：玩家可能正处在原版第三人称、床上或旁观者视角，接管瞬间不能跳；
- **离开视角时**：混合动画的终点必须精确等于这一帧的原版画面。

`lerp()` 对偏航取最短弧，所以 350°→10° 只走 20°。**两个端点必须同模式**，相机桥会先转换起点。

---

## 2. `VisionState` + `VisionController` —— 状态机

```java
VANILLA ──(F9 调整)──▶ SCULPTING ──(F9/F8 保存)──▶ ACTIVE ──(F8 切换)──▶ LEAVING ──▶ VANILLA
                            └────────(丢弃键 放弃)────────▶ 回到进入前的状态
```

**调整是显式的模式，不是"按住"**：按一次进入、再按一次保存、丢弃键放弃。玩家可以慢慢看面板、
中途去改设置、alt-tab 回来也不会丢机位。`LEAVING` 是真实状态而非标志位，
让「已经切走但还在退场动画里」这段时间走同一套求值路径，动画结束时不需要对齐补帧。

### 输入的三层接管

| 输入 | 接管点 | 效果 |
|---|---|---|
| 鼠标 | `Entity#turn` HEAD，`cancel()` | 原版已算好灵敏度/反转/平滑/开镜减速，直接喂给 `handleMouseTurn`；cancel 掉所以身体不转 |
| 移动键 | `KeyboardInput#tick` TAIL | 把 `up/down/left/right/jumping/shiftKeyDown` 清零；原始按键仍从 `Options.keyUp` 读给镜头 |
| 游戏键位 | `Minecraft#handleKeybinds` HEAD，`cancel()` | 调整期间不丢物品、不出手、不切 F5；同时排空 `Options.keyMappings` 的全部点击，退出后不会补触发 |

**`Esc` 完全不被拦截**，任何时候都能打开暂停菜单（无障碍复审的硬要求：上一版把 Esc 当"放弃"，
玩家在调整模式里既开不了设置也退不了存档）。放弃交给独立的丢弃键。
界面打开时编辑自然暂停（原版会释放 `KeyMapping`），关掉后继续；`VisionController#tick`
在 `screen != null` 时直接返回，不会隔着界面对状态做任何改动。

### 键位

全部是原生 `KeyMapping`，可在控制设置里改；提示文本统一通过
`KeyMapping#getTranslatedKeyMessage()` 取**玩家实际绑定的键名**，从不硬编码 "F8"。

| 字段 | 默认 | 语义 |
|---|---|---|
| `vision` | F8 | 开关已保存视角 |
| `edit` | F9 | 进入调整 / 保存 |
| `lock` | V | 切换镜头锁定 |
| `discard` | 退格 | 放弃本次调整 |
| `lookUp/Down/Left/Right` | 方向键 | 不用鼠标转镜头 |
| `dollyIn/Out` | `[` `]` | 不用滚轮推拉 |
| `rollLeft/rollRight` | `,` `.` | 翻滚 |
| `reset` | R | 机位归位 |

---

## 3. `VisionCameraBridge` —— 每帧一次的解算

`Camera#setup` 每帧跑一次，注入点在其 TAIL。此刻 `camera.getPosition()/getYRot()/getXRot()`
是**原版刚算出来的**值，我们随后整体覆盖。

```java
public static CameraPose computePose(Entity entity, Camera camera, float partialTick, Vec3 eye)
```

流程：

1. `VANILLA` → 返回 `null`，一个字节都不碰；
2. `LEAVING` 的目标是**这一帧的原版相机**，其它状态的目标是 `targetPose()`；
3. 起点与目标模式不同时，先把起点 `convertMode` 过去；
4. `progress ≥ 1` 直接返回目标；`LEAVING` 则返回 `null` 并把状态收成 `VANILLA`；
5. 否则 `lerp(from, target, smoothstep(progress))`，并 `recordApplied(pose)` 供下次无缝接续。

**混合在 rig 空间而非世界空间**，所以动画中玩家走动、转身都不会让镜头"追不上"。
`smoothstep(t) = t²(3-2t)` 两端导数为 0；进度用墙上时钟，卡帧也不会提速。

### 遮挡裁剪

`computePosition()` 在 `ACTIVE` / `LEAVING`（且 `avoidClipping` 开启）时，从眼睛到机位做一次
`ClipContext.Block.VISUAL` 射线，撞到方块就把机位收到命中点前 0.15 格。`SCULPTING` 不裁剪，
方便把机位摆进任何地方先看清楚。

---

## 4. 渲染侧

### 4.1 让玩家自己出现在画面里

`LevelRenderer` 只在这个条件下渲染「当前相机实体」：

```java
entity != camera.getEntity() || camera.isDetached() || ...
```

相机实体仍是玩家，所以机位一旦离开头部就置 `camera.detached = true`；贴着双眼时（不到 0.14 格）
保持 `false`，画面退化成正常第一人称，而不是看到自己脑袋的内壁。

### 4.2 藏掉手与贴屏特效

`GameRenderer#renderItemInHand` 整段 `cancel()`（顺带少一次投影矩阵重置，无害）；
南瓜模糊、望远镜边框、粉雪描边由 `GuiOverlayMixin` 在各自方法 HEAD 处 `cancel()`。
两者都只在「镜头真的离开头部」时生效，**不改 `Options` 的真实值**，玩家的 F5 设置原样保留。

### 4.3 准星

`GameRenderer#pick` 的 TAIL 里重做一次拾取（`VisionPicker`）：从相机位置沿相机视线打 4.5 格
（创造/旁观按原版 5/6 格），方块用 `Block.OUTLINE`、实体用 `ProjectileUtil.getEntityHitResult`，取更近者。
**安全边界**：命中点离玩家眼睛超过原版手长就丢弃、保留原版结果——永远不会发出服务端会拒绝的交互。

### 4.4 HUD：刻意不用 mixin 的唯一一处

HUD 走 Architectury 的 `ClientGuiEvent.RENDER_HUD`，而不是注入 `Gui#render`。

原因是被 Forge 教做人：Forge 把 `Minecraft.gui` 换成了
`net.minecraftforge.client.gui.overlay.ForgeGui`，它**重写了 `render` 且不再调用 `Gui#render`**。
任何挂在 `Gui#render` 上的注入在 Forge 端都是死代码——不报错、不崩溃、就是不显示，最难查。
`ClientGuiEvent.RENDER_HUD` 在 Forge 侧由 `RenderGuiEvent.Post` 驱动、Fabric 侧由 Architectury 自己的
注入驱动，两端都能拿到 `GuiGraphics`，是本项目唯一真正跨平台的绘制入口。

`GuiOverlayMixin` 用 `@Inject`（**不用 `@Redirect`**，见下）在 Fabric 上压掉南瓜/望远镜/粉雪特效；
Forge 这些装饰由它自己的 overlay 系统绘制，属于已知端差异。

> 为什么不写 `@Redirect`：MixinExtras 0.5.4 的 `FactoryRedirectWrapperMixinTransformer` 会把 `@Redirect`
> 的 `at` 直接强转成 `AnnotationNode`，而 javac 对数组型元素写的是列表，于是 `ClassCastException`
> 让目标类转换失败——装进任何捆了该版本的整合包都会直接启动崩溃。本项目所有注入只用 `@Inject` + `@At`。

---

## 5. 注入点总表

| Mixin | 目标 | 位置 | 作用 |
|---|---|---|---|
| `CameraMixin` | `Camera#setup` | TAIL | 写入机位与翻滚，按需置 `detached` |
| `CameraEyeHeightAccessor` | `Camera` 字段 | — | 读原版平滑眼高 |
| `EntityTurnMixin` | `Entity#turn` | HEAD/cancel | 鼠标转镜头，身体不动 |
| `KeyboardInputMixin` | `KeyboardInput#tick` | TAIL | 冻结玩家移动 |
| `MinecraftMixin` | `Minecraft#tick` | HEAD | 驱动状态机 |
| `MinecraftMixin` | `Minecraft#handleKeybinds` | HEAD/cancel | 静音游戏键位 |
| `GameRendererMixin` | `GameRenderer#pick` | TAIL | 相机准星 |
| `GameRendererMixin` | `GameRenderer#renderItemInHand` | HEAD/cancel | 藏掉第一人称手 |
| `GameRendererMixin` | `GameRenderer#bobView` | HEAD/cancel | 关掉跑动视角抖动 |
| `GuiOverlayMixin` | `Gui#renderSpyglassOverlay` / `#renderTextureOverlay` | HEAD/cancel | 第一人称贴屏特效（仅 Fabric） |
| `MouseHandlerMixin` | `MouseHandler#onScroll` | HEAD/cancel | 滚轮推拉机位 |

翻滚在 `Camera#setRotation`（只认 yaw/pitch）之后，绕相机局部 Z 轴再旋转四元数一次，
并重新推导 `forwards/up/left`。

---

## 6. 可以怎么往下扩展

- **多套预设**：`VisionConfig` 只有一个 `pose`，换成列表即可，状态机不用动。
- **固定俯仰的过场机位**：镜头锁定 + 指定 `pitchOffset`，不需要改代码。
- **跟随缓动**：在 `CameraPose#position()` 加时间常数做指数平滑；注意 `VisionPicker` 的射线段共享这个位置。
- **新状态**：加进 `VisionState` 与 `VisionController#targetPose()`，渲染侧一行都不用改。

---

## 7. 平台差异备忘

- **不需要 refmap**：Loom 的 tiny-remapper mixin 扩展在打包时把注解里的名字改写成目标平台名
  （Fabric → `method_xxxxx` / `field_xxxxx`，Forge → `m_xxxxx_` / `f_xxxxx_`）。已核对两个产物。
- **Fabric**：`fabric.mod.json` 声明 `environment: client`，专用服务器不会加载。
- **Forge**：1.20.1 的 `@Mod` 没有 `dist` 参数，客户端部分通过 `DistExecutor.unsafeRunWhenOn`
  隔离在 `FreeVisionForgeClient` 里，专用服务器永远不会链接到客户端类。
