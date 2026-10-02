# PVZCE UI 切换与导航架构

> 与 `当前项目架构.md` 同定位：本文描述**代码实际形态**（as-built），不是规划。
> 范围：屏幕之间怎么切、关卡怎么进怎么出、覆盖层与对话框归谁管。
> 涉及文件：`client/PvzceClient.java`（屏幕栈与入场状态机）、`client/gui/Screen.java`（单屏职责）、
> `client/gui/screens/*`（具体界面）、`server/PvzceServer.java`（入场权威）、
> `common/network/PvzcePackets.java`（入场协议）。

---

## 1. 一句话

界面切换**只有两种机制**：`PvzceClient.screens` 的**屏幕栈**（整屏之间）与 `Screen.widgets` 的
**模态对话框栈**（同一屏之内）。菜单里的"进入关卡"只是**发一个包 + 打开一屏**，
真正切进游戏的是服务端回 `LevelInitS2C` 时的**整栈替换**。

```
菜单（栈）                                服务端
  LevelSelectScreen  ──ContinueLevelC2S─▶  createLevel(intent, ...)
  LevelSetupScreen   ──PlayLevelC2S────▶     ├─ 存档恢复 / 新局
  ChooseSeedsScreen  ──RestartLevelC2S─▶     └─ LevelInitS2C
        ▲                                          │
        └──────── setScreenReplacing(InGameScreen) ┘   ← 唯一"进入游戏"的那一步
```

---

## 2. 三层切换机制

| 层 | 载体 | 谁负责分发 | 切换单位 |
|---|---|---|---|
| 屏幕栈 | `gui/ScreenStack`（`PvzceClient.screens`） | `PvzceClient.run()` 每帧 | 整屏（**第一屏由 `openFirstScreen()` 决定**：没回答过引导页就是 `OnboardingScreen`，否则 `TitleScreen`） |
| 覆盖层 | `PvzceClient.overlay`（`gui/Overlay`） | 同上，**不进栈** | 浮层（控制台、聊天行） |
| 对话框栈 | `Screen.widgets` 里的 `Dialog` | `Screen.mouseXxx`（`final`，先给模态） | 屏内面板 |
| 控件 | `Screen.widgets` 里的 `AbstractWidget` | 同上 | 按钮/输入框/列表 |

**每帧只驱动栈顶那一屏**（`PvzceClient.run()`）：

```java
window.pollEvents();
connection.tick();
pollInput();                     // 事件只发给 overlay（若有）或 currentScreen()
Screen screen = currentScreen();
screen.initIfNeeded();           // 首次或 resize 后跑一次 init()
screen.tick();
render();                        // currentScreen().render()，然后 overlay.render()
```

覆盖层与它下面的屏**互不干扰**：屏照常 `tick()`、照常 `render()`，覆盖层只是画在它上面并且
独占输入。这在结构上成立，不再需要"如果是 ConsoleScreen 就特殊照顾"的分支。

---

## 3. 屏幕栈

### 3.1 API 只有四个方法

```java
setScreenReplacing(screen)   // replace —— "从现在起是另一段流程"；被丢下的屏收到 onRemoved()
openScreen(screen)           // push    —— "这一屏盖在上一屏上，返回要回来"
navigateBack()               // back    —— 离开当前屏，去哪由 Screen.backTarget() 说了算
closeScreen()                // 无处可弹时不动，留给"客户端自己收回一屏"的场景
currentScreen() / screenDepth()   // peek / 导航深度（覆盖层不计入）
```

**关键认知：栈深度不是导航深度。** 它只是"当前压着几屏"：

```
标题(1) → 世界列表(2) → 关卡列表(3) → 关卡准备(4) → 选卡(5)
通关后回到列表：关卡列表(1)     ← 同一个界面，深度从 3 变成 1
```

所以**界面不靠 `screenDepth()` 猜"我从哪来"，而是声明 `backTarget()`**：默认弹栈，
少数被客户端当作流程根的界面（关卡列表、结算页）自己指名目的地。深度判据在
`LevelSelectScreen.backTarget()` 里作为**条件**保留了下来——"我从列表进来还是从关卡回来"
本来就是关于"怎么进来的"的事实，而这一屏是唯一知道它的人。

### 3.2 谁用 replace，谁用 push

| 语义 | 方法 | 调用点 |
|---|---|---|
| 根/跳转 | `setScreenReplacing` | `showTitle` / `showWorldSelect`（＝`showTitle`）/ `showLevelList` / `onLevelInit` / `restartCurrentLevel` / `finishLevelAndShowList`，以及 `run()` 里的冒烟入口 |
| 嵌套 | `openScreen` | `Title→LevelSelect`（点玩家名 / 开始游戏）、`Title→Shop/Packs`（左下托盘的两个格子）、`LevelSelect→LevelSetup`、`LevelSelect→Almanac`、**`LevelSelect→LevelCollectionScreen`（点一个关卡集合）**、`Settings→Config/Video`、`InGame→Award`、`openEditor`、`ChooseSeedsScreen` |
| 声明的目的地 | `Navigation.replaceRoot(...)` | `LevelSelectScreen.backTarget()`（无下层时）、`AwardScreen.backTarget()` |
| 弹回 | `Navigation.POP`（默认） | 所有"返回/完成"按钮、`Screen.requestClose()` 默认实现、`Dialog` 的 ESC |

### 3.3 导航状态图

```
                    ┌──────────────────────────────────────────────┐
                    │                 TitleScreen                  │
                    │  左上：谁要玩游戏？（玩家列表＝世界列表）      │
                    │  中列：开始游戏 / 模组列表 / 设置 / 退出       │
                    │  左下：木托盘 = 商店 / 数据包（两个图标格子）  │
                    └───┬──────────────┬───────────────┬───────────┘
                 push   │              │ push          │ push
                        ▼              ▼               ▼
       ┌────────────────────────┐  ┌────────────┐  ┌──────────────┐
       │  LevelSelectScreen     │  │ ModsScreen │  │SettingsScreen│
       │  (关卡列表 + 图鉴按钮)  │  └────────────┘  └───┬──────────┘
       └──┬──────────┬──────────┘                       │ push
          │ push     │ push            ┌─────────────▼──────────────┐
          ▼          ▼                 │ConfigScreen / VideoSettings│
   ┌────────────┐  ┌──────────────┐    │KeybindScreen /             │
   │LevelSetup  │  │ EditorScreen │    │JevSettingsScreen /         │
   │(关卡准备)   │  │ (编辑器)      │    │ShopScreen / PackScreen     │
   └──┬─────────┘  └──────────────┘    └────────────────────────────┘
      │
      │ 开始游戏 → enterLevelFromMenu（带上玩家选的阵营）
      ▼
   ┌──────────────────┐
   │ ChooseSeedsScreen│  （有存档：跳过这一屏；卡组没得选时是"仅预览"过场）
   │ (选卡)            │
   └──┬───────────────┘
      │ PlayLevelC2S → 服务端 LevelInitS2C
      ▼
   ┌────────────────────────────────────────────┐
   │ InGameScreen    ← setScreenReplacing（整栈替换）
   │  ├ ESC → PauseDialog（对话框，不是新屏）
   │  ├ 胜利+奖励 → 领奖 → push AwardScreen
   │  └ 失败 → 点击离开 → finishLevelAndShowList()
   └────────────────────────────────────────────┘
```

> 控制台（`ConsoleOverlay`）与聊天行（`ChatOverlay`）不在图里：它们是覆盖层（§8），
> `/` 与 `T` 分别打开，都不参与导航。

---

## 4. 进入关卡

### 4.1 决策只有一处，但入口有五个

**唯一决策函数**：`PvzceClient.enterLevelFromMenu(info)`

```java
if (info.hasRunningSave())        requestLevel(id, false);        // ① 有存档：直接进，服务端弹框
else if (offersTeamChoice(info))  openScreen(LevelSetupScreen);   // ② 关卡有两方以上可玩：先问阵营
else if (skipsSeedScreen(info))   startLevelWithSeedsAndBuffs(…);  // ③ 关卡说它没有选卡这一问：直接开局
else                              openSeedSelection(info, false); // ④ 其余：选卡页
```

**③ 是关卡自己说的话**（`LevelDef.seedScreen`，JSON 里写 `"seed_screen": false`；今天是 4-5 与 2-5），
判据 `PvzceClient.skipsSeedScreen(info)` = 关卡声明了不弹 **且** 它没有可挑的增益页（有增益页就还得开，
那一页是真正的问题）。**它不与"固定卡组"划等号**：1-1 也固定卡，但它要那一屏 —— 它要在那里播开场对话。
反过来说，**固定卡组 + 不播对话的关卡必须自己声明这一句**：4-5 与 2-5 都写过，而 2-5 是在玩家报
"2-5 却弹出了选卡页面"之后才补上的（`seed_screen` 默认 true，没写就等于要那一屏）。服务端配合的一条：没有选卡页的关卡收到空的选择请求时按
"没人问过"处理（`SeedSelection.plan`），于是卡组是默认卡组而不是"只有固定卡"。

**④ 里的"选卡页"有两种形态，由关卡决定**：有卡可选就是真正的选卡；卡组没得选（关卡的固定卡填满卡槽，
或者这一关的卡由它自己发 —— 传送带）就是**仅预览过场**（`ChooseSeedsScreen.previewOnly`：不画面板、不画卡池、
点击直接落到草坪上，播完开场对话后 2.1 秒自动开局）。**传送带关卡以前是直接进关的**：那样连"这一关会来哪些僵尸"
都看不到 —— 关卡列表与选卡页之外没有第三处显示僵尸预览，所以它们现在也走这一屏（只是没得选）。

**阵营是关卡声明的**（`LevelDef.playable_teams` → 每条 `TeamInfo.playable`），所以"要不要问"是关卡数据
的回答而不是客户端的猜测：**只有一方可玩时 ② 整条分支不成立**，内置关卡因此从列表点进去就是选卡页/开局。
判据 `PvzceClient.offersTeamChoice(info)` 是纯函数，写在这一处。

| # | 入口 | 位置 | 走的路 |
|---|---|---|---|
| 1 | 关卡列表「继续游戏/下一步」 | `LevelSelectScreen.openSetup()` | 转发给 `enterLevelFromMenu`（与②③④同一决策） |
| 2 | 关卡准备「开始游戏」 | `LevelSetupScreen.startGame()` | 转发给 `enterLevelFromMenu`（同一决策，第二份调用） |
| 3 | 存档提示框「重新开始」 | `PvzceClient.openSeedSelectionForRestart` | 打开选卡界面（`onBack` 回到提示框）；取不到关卡信息、或这一关声明了 `seed_screen: false` 时回落到 `RestartLevelC2S`（重开不是一次选卡） |
| 4 | 暂停菜单「重新开始」 | `PvzceClient.restartCurrentLevel()` | `LeaveLevelC2S` → 清状态 → 选卡页（`onBack = showLevelList`；没得选的关卡是仅预览过场，会自己开始） |
| 5 | 编辑器「测试」 | `PvzceClient.testEditedLevel()` | `/reload` → 等 `LevelListS2C` → `enterLevelFromMenu`（`setLevelList` 里续上） |

> 把关卡列表那一屏的分支收进 `enterLevelFromMenu` 是必要的，不只是整洁：入口 5 与冒烟钩子都直接
> 调它，列表里那份判断它们走不到 —— 一个"有两方以上可玩"的关卡从编辑器进去会跳过阵营选择。

### 4.2 三个包，三套语义

| 包 | 谁发 | 语义 |
|---|---|---|
| `ContinueLevelC2S(levelId, world)` | 关卡列表的「继续游戏」/ 存档提示框的「继续」/ 编辑器测试与冒烟（`restart=false`） | "**加载我保存的那一局**"。不带卡组：卡槽与阵营都在存档里 |
| `PlayLevelC2S(levelId, world, restart, seeds)` | 选卡界面的「开始游戏」 | "**用这套卡组开一局**"。从列表来（`restart=false`）时有存档就加载并询问；从存档提示框的「重新开始」来（`restart=true`）则丢弃存档 |
| `RestartLevelC2S(levelId, world, seeds)` | 暂停菜单的「重新开始」/ 冒烟（`restart=true`） | "**丢掉存档，从头开始**"，不需要选卡界面 |

**这三个类型是分开的，不是一个包加一个 `restart` 布尔**：布尔本身就等于整条消息，
旧实现要在一个 `restart` 布尔之外再自己跟踪一个 `confirmed` 布尔，才能拼出玩家到底选了
「继续 / 重开 / 用这套卡开一局」中的哪一个。

**服务端 `createLevel(levelId, world, intent, requestedSeeds)`**（`PvzceServer.java`）对三者统一处理，
`intent` 由**包的类型**翻译而来，服务端不再需要"玩家是否已经表过态"的布尔：

```java
// A save is a run to resume unless the player has already said no to it.
boolean loadSave = (intent == CONTINUE || intent == PLAY) && hasSave;
if (loadedSave) {
    savePromptPending = true;                 // 冻住关卡 tick（run() 里 !savePromptPending && !manualPause）
    connection.send(new LevelSavePromptS2C(...));  // 存档已经加载并渲染在背后，然后才问
}
```

四种 intent 与旧实现的行为**逐格等价**（`fresh`＝"不接受已经在跑的那个实例"）：

| 包 | intent | resync 已在跑的实例 | 读存档 | 删存档 | 弹存档框 |
|---|---|---|---|---|---|
| `ContinueLevelC2S` | `CONTINUE` | ✅ | ✅ | — | ✅ |
| `PlayLevelC2S(restart=false)` | `PLAY` | — | ✅ | — | ✅ |
| `PlayLevelC2S(restart=true)` | `PLAY_OVER_SAVE` | — | — | ✅ | — |
| `RestartLevelC2S` | `RESTART` | — | — | ✅ | — |

### 4.3 进入关卡的完整时序

```
LevelSelectScreen                PvzceClient                 PvzceServer                InGameScreen
      │                               │                          │                          │
      │ 点「继续游戏」                  │                          │                          │
      ├──openSetup()─────────────────▶│                          │                          │
      │                        hasRunningSave?                    │                          │
      │                          ├─ 是 ─▶ ContinueLevelC2S ──────▶ createLevel(CONTINUE)
      │                          └─ 否 ─▶ push(LevelSetup)         │   ├─ 读 level.dat
      │                               │    → ChooseSeedsScreen    │   ├─ restoreLevel()
      │                               │    → PlayLevelC2S         │   ├─ silenceClientLevelMusic()  ← 3 条 reset，在 init 之前
      │                               │                          │   ├─ sendFullState() → LevelInitS2C
      │                               │                          │   └─ LevelSavePromptS2C（仅当加载了存档且未确认）
      │                               │                          │                          │
      │                               │ ◀──── LevelInitS2C ──────┤                          │
      │                        onLevelInit():                    │                          │
      │                          particles.clear / animations.clear                         │
      │                          music.startLevel(grasswalk)                                │
      │                          setScreenReplacing(────────────────────────────────────────▶ 新实例
      │                          if (deferredSavePrompt) showLevelSavePrompt()              │
```

**三个不变量**（都是踩过坑换来的，改动时必须保住）：

1. **`LevelInitS2C` 是"关卡真的开了"的唯一信号**，`onLevelInit` 用 `setScreenReplacing` 重建整个栈。
   选卡界面**不关闭自己** —— 所以暂停框/选卡/关卡准备不可能残留在新关卡下面。
2. **存档提示框在 `LevelInitS2C` 之后发**，即"先把存档加载并渲染出来，再问要不要它"。
   客户端在 `onLevelInit` 里补开提示框（`showLevelSavePrompt` 若当前不是 `InGameScreen` 就先存进
   `deferredSavePrompt`）。
3. **音乐 reset 必须在 `LevelInitS2C` 之前**（`silenceClientLevelMusic()`）：init 才是启动新轨道的那一步；
   同一实例的 resync 不发 reset（它的音乐就是当前状态）。

### 4.4 "有存档"是一个独立字段

`LevelInfo.hasRunningSave()` 读的是 `LevelInfo.runningSave`，**不是 `status` 字符串**。

> `status` 只决定列表行上显示什么（有存档 → "进行中"，否则 → "已通关"）。
> "已通关 + 有一局放弃的重玩"两者同时成立，压成一个字符串就表达不了，
> 于是进入判定以为没有存档 → 打开选卡 → 提交后服务端才发现有存档 → 才弹框。
> 这个症状复发过一次，所以两个事实必须是两个字段。

---

## 5. 出关卡

| 出口 | 触发 | 动作 | 落到哪一屏 |
|---|---|---|---|
| 暂停「继续游戏」 | `PauseDialog` 第 1 个按钮 | `close()` → `PauseGameC2S(false)` | 留在 `InGameScreen` |
| 暂停「重新开始」 | `PauseDialog` 第 2 个按钮 | `LeaveLevelC2S` → `clearLevelClientState()` → 选卡页（没得选的关卡是仅预览过场） | `ChooseSeedsScreen`（`onBack = showLevelList`） |
| 暂停「保存并退出」 | `PauseDialog` 第 3 个按钮 | `close()` → `leaveLevel()` = `LeaveLevelC2S` + 清状态 | `TitleScreen`（玩家列表） |
| 胜利 + 奖励 | `InGameScreen.showReward` → 点击领取 | 播胜利音乐 → `openAwardScreen()`（push） | `AwardScreen` |
| 奖励页「继续」 | `AwardScreen` 按钮 / `requestClose()` | `finishLevelAndShowList()` | `LevelSelectScreen` |
| 失败 | 点击任意处 | `finishLevelAndShowList()` | `LevelSelectScreen` |

**`clearLevelClientState()` 是唯一一份"丢掉本关所有客户端状态"**（`:1550`）：
`savePromptOpen` / `deferredSavePrompt` / `directDialogueLevelId` / 音乐 / 粒子 / 涟漪 / 动画 / `level.reset()`。
`leaveLevel()`、`restartCurrentLevel()`、`finishLevelAndShowList()` 都调它 —— 不允许各自清一半。

**`restartCurrentLevel()` 的顺序有意义**：

```
LeaveLevelC2S  →  服务端 leaveLevel() 先存档再 shutdown（放弃重开也不丢进度，列表里仍是"进行中"）
clearLevelClientState()
setScreenReplacing(选卡)  ← 不是 push：旧关卡不会以冻结实例留在栈下面
```

（旧实现把选卡 `push` 在仍在运行的关卡之上，按 ESC 会退回旧关卡的暂停菜单，看起来就是"选了重新开始，旧关卡却还开着"。）

---

## 6. 屏内：对话框栈与输入分发

`Screen` 同时是"一屏"和"这一屏的输入路由器"。输入分发顺序是**强制的**，
`mouseClicked` / `mouseMoved` / `mouseDragged` / `mouseReleased` / `mouseScrolled` 都是 `final`：

```
事件 → Screen.mouseXxx（final，做坐标系换算）
        ├─ modalDialog() != null → 只给那个对话框，然后 return
        ├─ 遍历 widgets → 第一个消费掉它的控件拿走键盘焦点
        └─ 都没有 → 界面自己的钩子 onMouseClicked / onMouseMoved / onMouseScrolled / onMouseDragged
```

**界面要处理自己的点击区域，覆写钩子，不要覆写 `mouseClicked`。**
**约束：界面自己判定点击范围时不要越过控件。** 曾经有界面先判定自己那块占满窗口的区域再委托，于是对话框的按钮
全部点不动；现在 `mouseClicked` 是 `final`，模态分发先过它，结构上不可能再绕过。

`Dialog` 是**屏内**的模态层，不是新屏：

- `InGameScreen` 的暂停菜单是 `PauseDialog`（`Dialog`），不是 `PauseScreen` —— 棋盘保持可见；
- `DialogueOverlay`（关卡开场对话）也是 `Dialog`，所以"对话期间选卡与按钮点不动"是结构上成立的；
- `Screen.onResize()` 会重建基础 widget 并把已打开的对话框按新尺寸放回（对话框不会因改窗口而消失），
  靠"字段里已存在的 dialog 不再重复 add"这条隐式合约。

---

## 7. 输入层：谁处理 ESC

ESC **不在 `Screen` 里统一处理**，而是 `PvzceClient.pollInput()` 里的分支：

```java
if (overlay != null) {                 // 覆盖层开着时，键盘整个归它
    overlay.keyPressed(key);
} else if (key == GLFW_KEY_ESCAPE
        && !(screen instanceof InGameScreen)
        && !(screen instanceof EditorScreen)) {
    navigateBack();                    // 去哪由当前屏的 backTarget() 说了算
}
```

`InGameScreen` 与 `EditorScreen` 自己处理 ESC（游戏内要开暂停框、编辑器按页处理）；
其余界面走 `requestClose()`，默认实现就是 `client.navigateBack()`，
`AwardScreen` / `ChooseSeedsScreen` / `LevelSelectScreen` 各自覆写。

**关卡集合这一层是纯 push**：`LevelSelectScreen` 选中一个集合行后 `openScreen(new
LevelCollectionScreen(client, id))`，那张屏 `backTarget()` **无条件 `POP`**（它只可能由列表压上来，
而列表自己有"无下层时回标题页"的第二条路，见 `LevelSelectScreen.backTarget()`）。集合页不进关卡准备的
决策链：它的「下一步」把选中的**成员关卡**交给同一个 `client.enterLevelFromMenu`，与列表上按那个关卡
走的是同一条路（`UI切换与导航架构.md` §4 的入口表里属于第 ① 行）。

`/` 与 `T` 在**没有文本输入焦点**时各开一个覆盖层：`/` 开控制台（`openConsole`，预填一个 `/`），
`T` 开聊天行（`openChat`）。两者都抑制紧随其后的那一个字符事件（`suppressNextChar`），
因为打开它们的那个按键同时也会产生一个字符。

---

## 8. 覆盖层：控制台与聊天行

`ConsoleOverlay`（`client/gui/ConsoleOverlay.java`）与 `ChatOverlay`（`client/gui/ChatOverlay.java`）
是"浮在别的屏上面"的两种界面，它们**不在屏幕栈里**：

```java
// PvzceClient：一个槽位，一个分发规则
private Overlay overlay;                 // 打开时 new，关闭时置空
render()      -> currentScreen().render(); if (overlay != null) overlay.render();
pollInput()   -> if (overlay != null) overlay.keyPressed(key); else <屏幕的按键路径>
```

`Overlay` 与 `Screen` 同形但更小：`widgets` + 一个 `FocusManager` + 自己的输入处理，
`init()` 同样是懒执行（`initIfNeeded()`），`onResize()` 丢掉 widget 让它们按新尺寸重建
（控制台要自己把已输入的文本存下来再放回去）。

**为什么不能压栈**（旧实现的代价）：

1. `screenDepth()` 语义被污染 —— 控制台开着时深度多 1，任何按深度判断的界面都会读错；
2. 客户端要在两处特判它才能让下面的屏继续 `tick`/`render`；
3. 再加第二个覆盖层就要再加两处特判。

现在加一个覆盖层 = 写一个 `Overlay` 子类 + 在 `PvzceClient` 里给它一个打开入口，
帧循环一行都不用改 —— 聊天行就是这么加进来的：它是第二个实现，`PvzceClient` 里只多了
`openChat()` 一个入口和"哪种浮层用哪个键"的两个分支。

> 两者的分工是**键与形状**，不是同一件的两份：控制台是一个面板（工具，开着待一会儿），
> 聊天行是屏幕底部的一条（说一句就忘）。它们画的是同一份消息表（`ClientLevel.messages()`），
> 因为服务端说的话只有一份。

---

## 9. 这一层的现状

三层切换的机制本身没有待修的已知不一致：`POP` 是默认、无处可弹会报错并留在原地；`replaceRoot` 只在
"客户端把这一屏当作流程根"时用；控制台是独立的 overlay 槽位；屏离开栈时 `onRemoved()` 一定被通知一次；
关卡列表区分「猜的页」与「选的页」（`pagePickedByUser`），刷新只覆盖前者。
> 这些当初是**修出来的**（三处不一致与三条顺带项），排查过程在 `架构变更记录/2026-09-UI导航的三处不一致（补记）.md`。

## 10. 扩展点：想加东西该接哪

| 想加什么 | 接哪 | 现状 |
|---|---|---|
| 新界面 | 继承 `Screen`，`addWidget` / `showDialog`，自己的点击区写 `onMouseClicked` | ✅ 现成 |
| 新覆盖层 | 继承 `Overlay` + 在 `PvzceClient` 给一个打开入口 | ✅ 现成（帧循环不用改） |
| 屏内的确认框/输入框 | `Dialog` + `Screen.showDialog` | ✅ 现成 |
| 需要"离开时释放"的东西 | 覆写 `Screen.onRemoved()` | ✅ 现成 |
| 新前置流程（选难度、选阵营） | 仍要改 `enterLevelFromMenu` 的分支 | ⚠️ 待做（阵营已经是关卡数据驱动的：`LevelDef.playable_teams`） |
| 玩家/世界切换 | 标题页的木牌 → `PlayerPickerDialog`（只切换，不开关卡列表） | ✅ 玩家列表就是世界列表 |
| 新的"菜单页"（固定构图、缩放到窗口） | `layout/MenuPageCanvas` + `Canvas.panel/widgetAt` | ✅ 现成（商店页与数据包页是它的两个调用者） |
| 标题页左下角再添一个入口 | `TitleScreen` 的托盘格子（`cellAt` / `cellX` / `cellY` 三个方法一处定义，画与判定共用） | ✅ 现成（格子只有图标，名字走 `HoverTip`） |
| 关卡对话里的玩家名 | 台词里写 `${user_name}`，替换在 `DialogueOverlay.create` | ✅ 见 `当前项目架构.md` §6.2.2 |
| 关卡自带的开场对话 | 关卡 JSON 的 `dialogue` 块，宿主是选卡页或游戏内 | ✅ 数据驱动 |
| 新的"自供卡组"机制 | `common/level/mechanic` + `ClientMechanics` 路由 | ✅ 注册制 |
| 新关卡分类页 | `level_theme` / `level_category` 注册表 + id 路径 | ✅ 数据驱动 |

**关卡入场的扩展瓶颈**：加一屏前置流程 = 改 5 个入口里的分支 + 3 个跨往返字段
（`directDialogueLevelId` / `deferredSavePrompt` / `savePromptOpen`），
因为这些状态没有一个统一的归属。协议那一半已经收口了（见 §4.2），客户端这半边的收口方向是
把它们合成一个显式的 `LevelEntryRequest`（record：levelId / world / seeds / 意图 / 对话展示方），
加一屏 = 加一个 phase。

---

## 11. 约定（避免重新长出第二份实现）

1. **界面自己的鼠标/滚轮判定 → `Screen.onMouseClicked` / `onMouseMoved` / `onMouseScrolled`**，
   `mouseClicked` 等方法在 `Screen` 里是 `final`，模态分发靠它们保证。
2. **取最上层模态对话框 → `Screen.modalDialog()`**，不要自己倒序扫 `widgets`。
3. **"进入这一关"的决策 → `PvzceClient.enterLevelFromMenu`**。
   界面只负责"我选中了哪一关"，不要自己判断有没有存档、要不要选卡、要不要问阵营 —— 这三件事都在那一个函数里，界面里的第四份判断（编辑器测试、冒烟钩子）走不到它。
4. **"有没有存档" 与 "通关了没有" 是两个字段**（`LevelInfo.runningSave` 与 `status`），不要合并。
5. **进关卡只发一个包**：`ContinueLevelC2S` / `PlayLevelC2S` / `RestartLevelC2S` 三选一，
   不要混用，也不要再给它们加"这次算不算重开"的布尔。
6. **丢本关客户端状态 → `clearLevelClientState()`**，不要各自清一半。
7. **进入游戏的那一步在 `PvzceClient.onLevelInit`**，界面不要自己 `setScreenReplacing(InGameScreen)`。
8. **关卡开场对话的宿主取决于这一局怎么进的**：从菜单进的关卡在 `ChooseSeedsScreen` 播（没得选的关卡
   也在这一屏，只是面板不画），只有**绕过菜单**的那几条路（冒烟钩子、直接请求开局）才在
   `InGameScreen` 播，由 `directDialogueLevelId` 跨往返携带这个决定。
9. **背景 cover 适配 → `Screen.coverFit`**，按背景图像素写死的面板矩形要经它映射成点击区域。
10. **离开一屏要去哪 → `Screen.backTarget()`**。不要读 `screenDepth()` 来推断，也不要覆写
    `requestClose()` 去调用别的屏的展示方法：`POP` 是默认，只有"客户端把这一屏当作流程根"时才
    用 `replaceRoot`。
11. **离开时要释放的东西 → `Screen.onRemoved()`**，不要写进各自的出口方法。
12. **浮在别的屏上面的界面 → `Overlay`**，不要压进屏幕栈：栈深度是"嵌套了几屏"，不是"屏 + 浮层"。
14. **会滚的界面 → 声明 `onScrollRegionAt`**（返回 `client/input/ScrollRegion`），而且**必须复用滚轮处理器
    已经用的那个矩形**：不声明的话，滑动会在起点按下一次（商店行就是买、列表就是选中），声明了才把点击留到松手。
    控件级的列表用 `AbstractSelectionList` 自带的那份；压着列表的滑条靠 `claimsDrag()` 保住自己的拖动。
    新界面不许只提供滚轮一条路（触控下那条路不存在）。

13. **有真实构图的整屏页面 → `MenuPageCanvas`**（800x600 画布 + contain 缩放），不要直接在 GUI 单位里
    排版：默认窗口只有 427x240，直接排版出来的东西没有面板也没有层次（商店页与数据包页就是这么坏掉的）。
    控件仍留在 GUI 单位，用 `Canvas.widgetAt` / `interiorX/Width/Bottom/Height` 放进面板。
