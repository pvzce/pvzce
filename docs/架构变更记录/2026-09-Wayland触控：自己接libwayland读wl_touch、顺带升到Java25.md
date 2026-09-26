# 架构变更记录 · Wayland 触控：自己接 libwayland 读 wl_touch（FFM），顺带升到 Java 25

> **归档（冻结）**：这是从 `架构变更记录.md` 分卷出去的原文，只搬家不改写，**不再修订**。
> 索引见 [../架构变更记录.md](../架构变更记录.md)。

---

> 用户报："在 Wayland 下发现，完全无法点击。"；拍板："必须做 wayland 支持，因为新版本 ubuntu 移除了 XOrg。
> 你可以进行升级 java 版本和引入 FFM API。"

## 现象与根因

- **Linux 上"手指 = 鼠标"不是自动的**：Windows 由系统把主指针提升成鼠标消息；X11/Xorg 由 XInput2 为首个触点
  做指针模拟（GLFW 的 X11 后端只选核心事件，恰好收到那对模拟事件）；**Wayland 什么都不做** —— GLFW 3.4 的
  Wayland 后端源码里一处 touch 处理都没有（上游 issue #2876 / #7501 同现象），所以 Wayland 会话下（以及
  Xwayland 下）手指点击完全没有事件。上一轮那句"X11/XInput2 对首个触点做指针模拟"写得像普适事实，是错的。
- 用户机器上另一处独立现象：探针只建窗口不画东西 → **屏幕上什么都没有，Dock 里只有图标**。Wayland 的
  surface 没有 attach buffer 就是不可见的，这不是"窗口没建出来"。

## 定论

1. **不 fork GLFW，自己接 libwayland**：拿 `GLFWNativeWayland.glfwGetWaylandDisplay()/glfwGetWaylandWindow()`
   的句柄，用 **FFM（Java 25）** 走 `wl_proxy_marshal_array_flags` / `wl_proxy_add_listener` 绑 `wl_seat`
   → 读能力位 → `get_touch` → 监听。GLFW 的 Wayland 事件循环用的是默认队列
   （`wl_display_prepare_read/read_events/dispatch_pending`，无自定义 queue），所以我们建的对象会被
   `glfwPollEvents()` 一起派发，**不需要额外线程**，代价是几百行 FFM 而不是每平台一份打过补丁的原生库。
2. **平台偏好反转**：Wayland 会话（`WAYLAND_DISPLAY` 有值）优先**原生 Wayland**，`-Dpvzce.platform=x11` 仍可强制；
   否则"新 Ubuntu 上能玩、装了 Xwayland 的 Ubuntu 上不能"这种随发行版漂移的行为无法解释。启动时打一行
   `GLFW platform = …` 便于排查。
3. **触摸翻译成鼠标流，而不是新机制**：`client/input/TouchTranslator` 只跟第一根手指，把它变成
   `press/drag/release`（外加把触点记为指针位置，让种植幽灵跟着手指）；下游就是上一轮的 `PointerGesture`
   与帧循环那两条路，所以六处滚动区、按住扫阳光、拖卡到格子、小推车长按全部自动可用。
   `cancel`（合成器随时能收走触摸）按"松手"处理，不会让游戏一直抓着一张卡。
4. **失败永不致命**：没有 libwayland、seat 没有触摸、任何协议意外 → 一行日志 + 触摸层关闭，游戏照常能用鼠标键盘。
   `-Dpvzce.touch=false` 仍然一键关掉整条触控路径。
5. **Java 25**：FFM 在 22 定稿，且自 24 起每个入口都是 restricted method，需要
   `--enable-native-access=ALL-UNNAMED`（`run`/`test` 已在构建里带上，外部启动脚本也要带）。loader 侧无阻塞：
   ASM 9.10.1 认识 V25（还有 V26/V27）、Mixin 支持到 `JAVA_25`、fork 自己声明的上限就是 `JAVA_25`。
   `gradle.properties` 的 `java_version` 与 root build.gradle 里手写的 `options.release` 合成了一处。
6. **验证三件套**（这次没有靠"读代码觉得对"）：① 独立探针在用户真机上证明 seat 有触摸能力、`wl_touch`
   事件真的到达、坐标是 surface-local 逻辑像素；② 原生 Wayland 冒烟：`GLFW platform = 393219` +
   `触控：已接管 Wayland 触摸（wl_touch，单指）` + 胶片显示触摸滑动让卡条位移一张卡且**没有选中**手指下的
   那张、随后的触摸轻点让第三张卡亮起（选中态黄罩：蓝通道 124.7 → 100.3）；③ 单测 `TouchTranslatorTest`
   （第二根手指不重启手势、cancel 会松手、杂散事件无副作用）。
7. **冒烟多一个开关**：`pvzce.smokeTouchSource=true` 让 `smokeClick` / `smokeDragTo` 走触摸翻译器而不是
   鼠标分发——"手指这条路"从此也能截图对比（原生投递仍只能在真机上验）。

---

## 用户报的后续两件事（同一轮修完）

### 全屏下窗口"压根不出现"

- **现象**：窗口模式（`fullscreen=false`）一切正常、触控也能用；全屏时窗口完全看不到，而且不在别的显示器上。
- **根因**：Wayland 下 GLFW 的 xdg_toplevel 是**在 `glfwShowWindow` 里才创建**的
  （`_glfwCreateWindowWayland` 只在 `window->monitor || wndconfig->visible` 时建；我们 `VISIBLE=FALSE`
  且创建时不带 monitor），而全屏请求在 `acquireMonitor` 里被 `else if (window->wl.xdg.toplevel)` 挡着。
  旧顺序"先 `setFullscreen(true)`、再 show"于是**把请求丢掉了**：GLFW 内部状态已记成全屏、idle inhibitor
  也开了，游戏按显示器尺寸渲染，而合成器从没被告知要全屏。"窗口压根不出现"就是这个状态的样子。
- **定论**：`PvzceWindow` 的构造顺序改成**先 show、再进全屏**（X11 无害：那边窗口一创建就存在）。
  启动行补报主显示器模式，下一次这类报告第一眼就能确认"全屏落在哪块屏、多大"。

### 用户报的第三件事：按钮亮着但点不动（坐标空间混用）

- **现象**："点击按钮，按钮显示被压下去了，但根本点不动，感觉就像只是把鼠标移上去了一样"；连「退出」也点不动
  （窗口不关，用鼠标排除后确认是触摸这条链）。
- **根因**：`wl_touch` 给的是 **surface 像素**（= 光标回调那一套坐标），而 `PointerGesture` 的滚动区判定与
  `Screen.dispatchMouseClicked` 要的是 **GUI 逻辑像素**。接线时把像素原样喂给了两者 —— 指针位置
  （`setPointerPosition`）恰好要像素，所以**悬停是对的、按钮会亮**（`Button.render` 里 `active && hovered`
  就是"按下"贴图），而点击落在"GUI(753,365)"这种 UI 从未听说过的地方（那一屏的 GUI 空间只有 427×240），
  于是什么都不会发生。`guiScale = 1` 时两个空间恰好相等，所以只有缩放 UI 才暴露。
- **为什么绿的冒烟没挡住**：`deliverGuiTouch` 这个钩子收的是 **GUI 坐标**、直接喂翻译器，**绕过了真实设备
  经过的那次换算**——它验的是一条真机上不存在的路径。
- **定论**：换算收在唯一入口 `PvzceClient.touchSink`（native 像素进来就换一次，指针位置留像素、
  手势与点击拿 GUI），`TouchTranslator` 的契约写明"逻辑 GUI 像素"；**冒烟钩子改走同一个 sink**
  （先把 GUI 换算成屏幕坐标再喂），从此截图验的就是真路径。验证：触摸点「退出」窗口直接关闭、
  点「开始游戏」玩家选择器打开。

---



- **现象**：全屏启动时出现 `[GL] frame: 0x502`（`GL_INVALID_OPERATION`），窗口模式没有。
- **根因**：`ShaderProgram` 构造时先设一批初始 uniform，却**没有先 bind 自己**；`glUniform*` 作用于
  "当前程序"，于是要么落在 0 号程序上（报警告），要么**静默改掉上一个程序的 uniform**。
- **定论**：`glUseProgram(id)` 提到那批 uniform 之前。顺带加了 `-Dpvzce.traceGl=true`
  （debug context + `KHR_debug` 同步输出）：这条错误就是驱动直接点名的 `glUniform(program not linked)`，
  否则手里只有一个 0x502、只能猜。

---
