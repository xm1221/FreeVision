# HUMAN.md · 维护手册

[English](HUMAN.md) · **简体中文**

这份文档给**维护者**看：怎么构建、东西放在哪、碰哪些地方会崩、出问题怎么查。
面向玩家的说明在 [README.zh-CN.md](README.zh-CN.md)，实现原理在 [docs/ARCHITECTURE.zh-CN.md](docs/ARCHITECTURE.zh-CN.md)。

---

## 1. 一分钟上手

```powershell
# Gradle 需要一个 JDK 21+ 的运行时（产物仍是 Java 17 字节码）
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'   # 换成本机路径
.\gradlew.bat build

# 产物
#   fabric\build\libs\freevision-fabric-1.0.0.jar
#   forge\build\libs\freevision-forge-1.0.0.jar

# 开发运行
.\gradlew.bat :fabric:runClient
.\gradlew.bat :forge:runClient
```

`runClient` 约 40 秒到标题屏。**日志里只要没有 `MixinApplyError` / `InjectionError` / `could not find`，
就说明所有注入点都命中了。** 这两条是排查启动崩溃的第一现场：

```powershell
Select-String -Path fabric\run\logs\latest.log -Pattern "Mixin|Injection|freevision"
```

Gradle 缓存里已有 MC 1.20.1 与依赖，第二次构建 10 秒左右。**不要**用 JDK 17 跑 Gradle：
Loom 1.17 要求构建 JVM ≥ 21。

---

## 2. 目录地图

```
common/src/main/java/cn/xm1221/freevision/
├── camera/
│   ├── CameraPose.java          ← 机位数据 + 全部数学（唯一需要"想清楚"的文件）
│   ├── VisionState.java         ← 四个状态
│   ├── VisionController.java    ← 状态机、键位处理、提示、音效
│   ├── VisionCameraBridge.java  ← 每帧把状态解算成一个机位
│   └── VisionPicker.java        ← 准星从相机重打射线
├── config/
│   ├── VisionConfig.java        ← 读写 config/freevision.json，加载时清洗
│   └── VisionSettings.java      ← 用户可调项 + sanitize()
├── client/
│   ├── VisionKeybinds.java      ← 所有 KeyMapping
│   ├── VisionHud.java           ← 调整面板 + 提示条
│   └── FreeVisionClient.java    ← 启动入口（由平台侧调用）
└── mixin/                       ← 注入点，见下表
```

平台侧各两个文件：`fabric/.../FreeVisionFabric.java`、`forge/.../FreeVisionForge(+Client).java`。
它们**只负责注册键位**，其余全部在 common。

---

## 3. 注入点：改代码前必须知道的事

| Mixin | 目标 | 作用 | 破坏后果 |
|---|---|---|---|
| `CameraMixin` | `Camera#setup` TAIL | 写入机位、翻滚、`detached` | 相机不生效 |
| `CameraEyeHeightAccessor` | `Camera.eyeHeight*` | 读原版平滑眼高 | 蹲下时画面跳 |
| `EntityTurnMixin` | `Entity#turn` HEAD | 鼠标转镜头 | 转头会转人物 |
| `KeyboardInputMixin` | `KeyboardInput#tick` TAIL | 冻结玩家移动 | 调镜头时人物乱走 |
| `MinecraftMixin` | `Minecraft#tick` HEAD | 驱动状态机 | 整个模组不动作 |
| `MinecraftMixin` | `Minecraft#handleKeybinds` HEAD | 静音游戏键位 | 调整时误攻击/丢物品 |
| `GameRendererMixin` | `pick` / `renderItemInHand` / `bobView` | 准星 / 手模 / 视角抖动 | 准星错位、手悬空 |
| `GuiOverlayMixin` | `Gui#renderSpyglassOverlay` / `#renderTextureOverlay` | 第一人称贴屏特效（仅 Fabric） | 南瓜/望远镜遮罩乱入 |
| `MouseHandlerMixin` | `MouseHandler#onScroll` | 滚轮推拉 | 滚轮被 GUI 抢走 |

三条硬规则：

1. **默认 `defaultRequire = 1`**：任何一处目标找不到，游戏会在**启动时崩溃**。这是有意的——
   宁可崩也不要静默失效。升级 MC 版本时逐条核对上表的方法/描述符。
2. **永远不要用 `@Redirect`（也不要任何 MixinExtras 注解）。** MixinExtras 0.5.4 会把 `@Redirect`
   的 `at` 强转成 `AnnotationNode`，而 javac 写的是列表 → `ClassCastException` → 目标类转换失败
   → 游戏起不来。本项目全部使用 `@Inject` + `@At`。
3. **不要碰 Esc。** Esc 留在 `KeyboardHandler` 里，玩家随时能开暂停菜单去改键位——早期版本把 Esc
   当"放弃"，结果玩家被锁死在调整模式里。

### 核对 API 的正确姿势

本机缓存里有 mojmap 合并 jar，`javap` 比翻网页可靠：

```powershell
$jar = Get-ChildItem "$env:USERPROFILE\.gradle\caches\fabric-loom\minecraftMaven" -Recurse -Filter "minecraft-merged-1.20.1-*.jar" | Select-Object -First 1
& "C:\Program Files\Java\jdk-17\bin\javap.exe" -p -classpath $jar.FullName net.minecraft.client.Camera
```

需要看方法体时用工作区里未被版本管理的 `.tools/`（vineflower + 反编译产物）。**不要**把反编译产物提交进仓库。

---

## 4. 常见维护任务

### 改默认键位
`client/VisionKeybinds.java` 的 `create()`，改 `GLFW.GLFW_KEY_*`。控制界面会自动多出条目，
提示文本会自动显示新键名（全部走 `getTranslatedKeyMessage()`，**不要**在 lang 里写死 "F8"）。

### 加一个可调选项
1. `config/VisionSettings.java` 加字段（给默认值）；
2. 需要范围约束就写进 `sanitize()`（**一定要写**，配置是玩家可手改的）；
3. 使用处读 `VisionConfig.get().settings.xxx`。

### 改保存的视角
`VisionConfig` 只有一个 `pose` 字段（`null` = 还没存过）：

- `pose()` / `hasPose()` 读；
- `storePose(pose)` 写（内部 `copy()`，不与他人共享可变对象）；
- `clearPose()` 清空。

旧版本用 `presets` 列表 + `activeSlot` 存多槽位，`sanitise()` 会在加载时把选中的那条迁移到 `pose`
并丢掉旧字段。**不要再把它加回来**：那次把 `next_slot` 默认设成 F9，与新的调整键撞在同一个键上，
按键查表后者覆盖前者，结果按 F9 只会切槽位、进不了调整模式。

### 改机位数学（谨慎）
全部在 `CameraPose`：

- `cameraYaw / cameraPitch / position`：由「相对玩家的六个数字」算出世界机位；
- `fromWorld`：把任意原版相机折算回来（进出动画的起点和终点靠它）；
- `convertMode`：相对模式 ↔ 镜头锁定，切换瞬间画面必须不动；
- `lerp`：**两端必须同模式**，`VisionCameraBridge` 负责先转换。

改完跑一遍纯数学自检（不需要启动游戏）：

```powershell
& "C:\Program Files\Java\jdk-17\bin\java.exe" tools\RigMathCheck.java
# 期望：ALL RIG MATH CHECKS PASSED
```

它做 20 万组随机 `world → rig → world` 往返（误差应 ~1e-11）、绕玩家公转半径不变、最短弧插值。

### 改 HUD 文案
`common/src/main/resources/assets/freevision/lang/{en_us,zh_cn}.json`。

**1.20.1 只支持 `%s` 和 `%%`**——写 `%d` / `%.1f` 不会崩，但整条会静默退化成显示模板原文。
数值一律在 Java 侧 `String.format(Locale.ROOT, ...)`，占位符只放已经成型的字符串。

---

## 5. 坑与约定（都是踩过的）

| 坑 | 说明 |
|---|---|
| Gradle JVM 必须是 21+ | Loom 1.17 的 unpick 依赖要求 21；产物仍由 `options.release = 17` 编译 |
| 不需要 refmap | Loom 打包时会把注解里的名字改写成 intermediary/SRG；改动后可用 `javap -v` 验证 |
| `@Accessor` 的字段名也会被改写 | 别以为字符串不会被改，见 `CameraEyeHeightAccessor` |
| Forge 拿事件总线要**构造器注入** | `FMLJavaModLoadingContext.get()` 在 47.4 已 `forRemoval`。`FMLModContainer.constructMod` 会先找 `getDeclaredConstructor(FMLJavaModLoadingContext.class)`，失败才退回无参构造——所以写成 `public FreeVisionForge(FMLJavaModLoadingContext context)` |
| `pack.mcmeta` 放在**平台模块**而不是 common | 放 common 时 Forge 开发环境会报 `Missing metadata in pack mod:freevision`（dev 的 mod file 读不到 common 的资源目录）。放进 `fabric/`、`forge/` 各自的 `src/main/resources` 后，dev 与产物都安静，且每个 jar 里只有一个副本 |
| Shift / Ctrl 可能是 `ToggleKeyMapping` | 开了"切换潜行/疾跑"后 `isDown()` 会一直为 true；读物理按键请用 `physicallyDown()` |
| 两个动作绑同一个键 = 前一个**静默死亡** | `KeyMapping` 的按键表由 `Options.keyMappings` 重建，后注册的覆盖先注册的。`FreeVisionClient` 现在启动时会对重复绑定打 WARN |
| **Forge 的 GUI 不是 `Gui`** | Forge 把 `Minecraft.gui` 换成 `net.minecraftforge.client.gui.overlay.ForgeGui`，它**重写了 `render`** 且不调用 `Gui#render`。任何往 `Gui#render` 里注入的 HUD 在 Forge 上都是死代码（不报错、不崩溃、就是不显示）。HUD 必须走 Architectury 的 `ClientGuiEvent.RENDER_HUD`：Forge 侧挂 `RenderGuiEvent.Post`，Fabric 侧挂自己的注入 |
| 排查"画不出来"先看钩子有没有被调用 | 在 `VisionHud.render` 开头加一次性 `LOG.info`，跑一次看日志。这次就是靠它把范围从"渲染错误"缩到"钩子根本没跑" |
| **不要用 `@Redirect`** | MixinExtras **0.5.4** 在 `FactoryRedirectWrapperMixinTransformer` 里做 `(AnnotationNode) Annotations.getValue(redirect, "at")`，而 javac 对数组型元素写的是**列表**（`at=[@At(...)]`）→ `ClassCastException` → 目标类（这次是 `Gui`）转换失败 → **启动崩溃**。0.5.5 修了，所以本地开发环境（Loader 自带 0.5.5）永远复现不出来。现在全部用 `@Inject` + `@At`，并已核对两个产物里 `spongepowered/asm/mixin/injection/Redirect` 与 `com/llamalad7/mixinextras` 都是 0 引用 |
| 发布前扫一遍注解 | 解压 jar 里的 `cn/xm1221/freevision/mixin/*.class`，文本搜 `injection/Redirect` 和 `com/llamalad7`，必须为空。这是"装进别人包里就崩"的那类问题，自测跑不出来 |
| 别用 `isDown()` 判定模式切换 | 模式切换一律 `consumeClick()` + 去抖，且**一个 tick 只处理一次** |
| 相机离眼睛 < 0.14 格时 `detached = false` | 否则玩家会看到自己脑袋的内壁；手模隐藏与它绑定，避免"没手也没身体" |
| 配置是磁盘来的脏数据 | 加载时 `sanitise()` 会把 1e6 / NaN 拉回来，否则每帧裁剪射线会把客户端卡死 |
| 交互距离不能超过原版 | `VisionPicker` 命中点超过玩家手臂长度就丢弃、退回原版结果 |
| 提示时长 | 成功类 3.5s、需要照做类 6s；不要传 0（0 会被当成默认值） |

---

## 6. 发布检查单

- [ ] `.\gradlew.bat build` 通过，两个 jar 都产出
- [ ] `java tools\RigMathCheck.java` 通过
- [ ] `:fabric:runClient` 与 `:forge:runClient` 各启动一次，日志无 `MixinApplyError` / `InjectionError`
- [ ] 进世界实测：`F9` 调整 → `F9` 保存 → `F8` 开关 → `V` 锁定 → 丢弃键 → `Esc` 能开暂停菜单
- [ ] 改一个键位后确认提示文本显示的是新键名
- [ ] `README.md` 的键位表与 `VisionKeybinds` 一致
- [ ] 版本号：`gradle.properties` 的 `mod_version`
- [ ] 提交：`git add -A && git commit -m "..."`

---

## 7. 已知取舍（不是 bug，别"顺手修"）

- **F8/F9 是功能键**：Mac 与部分笔记本需要 Fn，但这是最初需求指定的键；想换就去控制设置改。
- **调整期间游戏键位全部静音**：包括背包与聊天——换来的是"绝不会误攻击/误丢物品"。
  退出方式是保存键、丢弃键或 Esc（Esc 会正常打开暂停菜单）。
- **镜头锁定下准星与角色朝向分离**：这是该模式的定义。
- **相机拾取按相机射线判定**：机位越远，可交互范围越接近原版；命中点超出玩家手臂长度会被丢弃。
- **配置不做运行时热重载**：改完重启生效（玩家改键位立刻生效，那是原版机制）。

---

## 8. 分支与提交

- 主线 `master`，一个功能一个提交，提交信息用英文、祈使句。
- 提交前确认 `git status` 干净；`.tools/`、`build/`、`run/` 都已在 `.gitignore` 里。
