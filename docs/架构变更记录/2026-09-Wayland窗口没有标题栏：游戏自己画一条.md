# Wayland 下窗口没有标题栏：游戏自己画一条

> 用户报："在 Linux 上，似乎窗口变成了无边框，上面的三大按键看不到，标题栏也没有了。"

## 现象与根因

- **一眼能验的现象**：启动行 `边框=[0, 0, 0, 0] 自绘标题栏=true`——GLFW 说这个窗口一点系统装饰都没有。
  同一台机器上 `Failed to load plugin 'libdecor-gtk.so': failed to init` /
  `No plugins found, falling back on no decorations` 两行就在它前面。
- **根因一：Wayland 上标题栏是 libdecor 的责任，而它的 GTK 插件在 JVM 里永远起不来。**
  libdecor-gtk 的 `libdecor_plugin_new` 开头是 `if (getpid() != gettid()) return NULL;`（"只支持在主线程运行"），
  而 JDK 的 java 启动器**故意**不在进程的首个线程上建 VM（[JDK-6316197](https://bugs.openjdk.org/browse/JDK-6316197)），
  所以 `gettid() != getpid()` 对任何 JVM 都成立。实测探针：JVM 里 `getpid=42 / gettid=44`，python3 里 `2/2`。
  这条 `return NULL` **不打任何日志**，于是外面只看到"插件加载失败"，看起来像 libdecor 没装好。
- **根因二：合成器也不兜底。** GNOME 的 Mutter 不广播 `zxdg_decoration_manager_v1`
  （本机 registry 实测没有这个 global），所以 `GLFW_WAYLAND_DISABLE_LIBDECOR` 也换不来服务端装饰；
  GLFW 自带的兜底装饰只在合成器回 `CLIENT_SIDE` 时才建，那条路走不到。
- 于是"Linux 上窗口无边框"不是哪一次改坏的，是 **Java + Wayland 的结构性死结**：能给出系统标题栏的两条路
  （libdecor / 服务端装饰）一条都走不通。

## 定论

1. **桌面不画，游戏就自己画**（`client/gui/WindowTitleBar`）：窗口化且 `glfwGetWindowFrameSize` 全 0 时，
   在帧的最上层画一条标题栏，右侧三个按键——最小化、最大化/还原、关闭。全屏不画，X11/Windows 上
   `frameInsets` 非 0 也不画，所以这条 UI 只在真正需要它的地方出现。
2. **几何与手势分成两半，各自可测**：`WindowTitleBar.Layout`（纯算术：高度按 GUI 高度 1.9% 并夹在 18~46，
   按键右对齐、宽度固定）与 `TitleBarGesture`（纯状态机：按下→抬起，落在哪个键上才算哪个键；
   空白处两次 400 ms 内的点击 = 双击，切换最大化）。用例 `WindowTitleBarTest` / `TitleBarGestureTest`
   把"按键在右边、close 最右、拖动整个手势都算 chrome、双击才最大化"钉住——这些规则在屏幕上
   肉眼看不出来，而写错一条就是"窗口点不动"。
3. **标题栏排在每条指针通路的最前面**：`dispatchMouseClicked`/`Dragged`/`Released` 与
   `gestureRegionAt` 都先问它，且 `dragging()` 覆盖**整个**按下到抬起的过程（不只是按在按键上的那一下），
   否则从标题栏起手的横扫会在最后一帧把牌拖走。画面盖在游戏上（不占 GUI 高度），因为游戏顶部本来就留白。
4. **不做拖拽移动窗口**：Wayland 客户端不能自己设位置（GLFW 直接报
   "The platform does not support setting the window position"），而 `xdg_toplevel.move` 需要 GLFW 私有的
   `xdg_toplevel` 与输入 serial，libdecor 那条路又走不通（第 2 条）。按偏移量从 GLFW 的私有 struct 里
   读指针是拿游戏进程的 SIGSEGV 赌一个偏移量，不做；Mutter 自己的 Alt+F7 仍然可用。
5. **`pvzce.smokeClick` 只发按下**：它的松手本来靠帧循环发现"键已经不在按下状态"，而合成点击不会让
   GLFW 的状态变化，于是"松手才生效"的控件（标题栏的关闭键、对话框的确认）在冒烟里**看着像死的**。
   补 `pvzce.smokeRelease=<帧数>`（可选，默认不发），与 `pvzce.smokeSwipe` 同一个理由：
   钩子必须走真实设备走的那条路。
6. 启动行补两个字段：`边框=[左, 上, 右, 下]` 与 `自绘标题栏=<bool>`——"标题栏不见了"这类报告从此一行可判。
