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
| 屏幕栈 | `gui/ScreenStack`（`PvzceClient.screens`） | `PvzceClient.run()` 每帧 | 整屏 |
| 覆盖层 | `PvzceClient.overlay`（`gui/Overlay`） | 同上，**不进栈** | 浮层（今天的唯一实现是控制台） |
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
| 根/跳转 | `setScreenReplacing` | `showTitle` / `showWorldSelect` / `showLevelList` / `onLevelInit` / `restartCurrentLevel` / `finishLevelAndShowList`，以及 `run()` 里的冒烟入口 |
| 嵌套 | `openScreen` | `Title→WorldSelect`、`WorldSelect→LevelSelect`、`LevelSelect→LevelSetup`、`LevelSelect→Inventory`、`Settings→Config/Video`、`InGame→Award`、`openEditor`、`ChooseSeedsScreen` |
| 声明的目的地 | `Navigation.replaceRoot(...)` | `LevelSelectScreen.backTarget()`（无下层时）、`AwardScreen.backTarget()` |
| 弹回 | `Navigation.POP`（默认） | 所有"返回/完成"按钮、`Screen.requestClose()` 默认实现、`Dialog` 的 ESC |

### 3.3 导航状态图

```
                    ┌──────────────────────────────────────────────┐
                    │                 TitleScreen                  │
                    │  开始游戏 / 模组列表 / 设置 / 退出            │
                    └───┬──────────────┬───────────────┬───────────┘
                 push   │              │ push          │ push
                        ▼              ▼               ▼
              ┌──────────────┐  ┌────────────┐  ┌──────────────┐
              │WorldSelect   │  │ ModsScreen │  │SettingsScreen│
              │(世界列表)     │  └────────────┘  └───┬──────────┘
              └───┬──────────┘                       │ push
                  │ push                             ▼
                  ▼                          ┌──────────────────┐
       ┌────────────────────────┐            │ConfigScreen /    │
       │  LevelSelectScreen     │            │VideoSettings     │
       │  (关卡列表 + 背包按钮)  │            └──────────────────┘
       └──┬──────────┬──────────┘
          │ push     │ push                    ┌──────────────┐
          ▼          ▼                         │InventoryScreen│
   ┌────────────┐  ┌──────────────┐           └──────────────┘
   │LevelSetup  │  │ EditorScreen │
   │(关卡准备)   │  │ (编辑器)      │
   └──┬─────────┘  └──────────────┘
      │ 开始游戏 → enterLevelFromMenu
      ▼
   ┌──────────────────┐
   │ ChooseSeedsScreen│  （有存档 / 传送带关卡：跳过这一屏）
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

> 控制台（`ConsoleOverlay`）不在图里：它是覆盖层（§8），`/` 或 `T` 打开，不参与导航。

---

## 4. 进入关卡

### 4.1 决策只有一处，但入口有五个

**唯一决策函数**：`PvzceClient.enterLevelFromMenu(info)`（`PvzceClient.java:1326`）

```java
if (info.hasRunningSave())        requestLevel(id, false);        // ① 有存档：直接进，服务端弹框
else if (dealsItsOwnCards(id))    requestFreshRunDirectly(id,false); // ② 传送带：直接进
else                              openSeedSelection(info, false); // ③ 其余：选卡
```

| # | 入口 | 位置 | 走的路 |
|---|---|---|---|
| 1 | 关卡列表「继续游戏/下一步」 | `LevelSelectScreen.openSetup()`（`:595`） | 有存档 → `enterLevelFromMenu`；否则 `push(LevelSetupScreen)` |
| 2 | 关卡准备「开始游戏」 | `LevelSetupScreen.startGame()`（`:113`） | 转发给 `enterLevelFromMenu`（同一决策，第二份调用） |
| 3 | 存档提示框「重新开始」 | `PvzceClient.openSeedSelectionForRestart` | 打开选卡界面（`onBack` 回到提示框）；取不到关卡信息时回落到 `RestartLevelC2S` |
| 4 | 暂停菜单「重新开始」 | `PvzceClient.restartCurrentLevel()`（`:1577`） | `LeaveLevelC2S` → 清状态 → 传送带则直接重开，否则选卡（`onBack = showLevelList`） |
| 5 | 编辑器「测试」 | `PvzceClient.testEditedLevel()`（`:1915`） | `/reload` → 等 `LevelListS2C` → 选卡（`setLevelList` 里续上） |

> 入口 1 和 2 都在判断"有没有存档"，是同一套规则的两份调用。这不是 bug（都转发到同一函数），
> 但它是"想加一屏前置流程时不知道该往哪插"的直接原因 —— 见 §10。

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
| 暂停「重新开始」 | `PauseDialog` 第 2 个按钮 | `LeaveLevelC2S` → `clearLevelClientState()` → 传送带直接重开，否则选卡 | `ChooseSeedsScreen`（`onBack = showLevelList`） |
| 暂停「保存并退出」 | `PauseDialog` 第 3 个按钮 | `close()` → `leaveLevel()` = `LeaveLevelC2S` + 清状态 | `WorldSelectScreen` |
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
（`LevelSelectScreen` 曾经先判定占满窗口的卡牌网格再委托，于是"新建关卡"对话框的按钮全部点不动。
现在结构上不可能再绕过。）

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

`/` 与 `T` 在**没有文本输入焦点**时打开控制台（`openConsole`），并抑制紧随其后的字符事件。

---

## 8. 覆盖层：控制台

`ConsoleOverlay`（`client/gui/ConsoleOverlay.java`，`Overlay` 的子类）是唯一一个"浮在别的屏上面"
的界面，它**不在屏幕栈里**：

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
帧循环一行都不用改。

---

## 9. 这一层曾经的三处不一致（已修）

| # | 曾经的形态 | 症状 | 现在 |
|---|---|---|---|
| 1 | `closeScreen()` 在栈深 1 时**静默 no-op** | 关卡列表的「返回」完全失效过（通关后栈里只有这一屏），当时只能靠深度判断打补丁 | `Screen.backTarget()` 声明目的地；无处可弹的 `POP` 会**报错并留在原地**，而不是假装成功 |
| 2 | 返回目标靠**深度猜测** | 从标题进来（深度 3）与通关回来（深度 1）返回目标恰好都是世界列表，所以"碰巧对"；`EditorScreen.requestClose()` 是裸 `closeScreen()`，编辑器若从"正在跑的关卡"里打开就会退回去 | 同上：`POP` 是默认，`replaceRoot` 是显式声明的例外 |
| 3 | 控制台是**覆盖层却做成栈条目** | 见 §8 | 独立的 overlay 槽位 |

**顺带修掉的两处**：

- **`onRemoved()` 生命周期钩子**：屏离开栈时一定被通知一次（`POP`、整栈替换、客户端关停都算），
  所以"`init()` 里申请的东西"终于有配对的释放点。`EditorScreen` 原先要在自己的两个出口各写一次
  `canvas.releaseCanvasAnimations()`，漏一处就泄漏一个动画 playback。
- **重开选卡不再写两份目的地**：`ChooseSeedsScreen` 的 `onBack` 现在**就是**"这是一次重开"的
  唯一声明（非空 ⇒ 有状态可回），`createSeedSelection` 从它推导 `restart`，不再有一个参数与
  另一处回调说同一件事。
- **关卡列表不再停在空桶页**：这一屏选页时看得见的只有一部分——关卡列表到达时还没有
  `theme`/`category`，服务端的页签表又晚一个往返。于是 `openTab` 是在**空视图**上被选出来的，
  而"未分类"永远是页签表的最后一项，所以那个猜测一路活到刷新之后：玩家打开「选择关卡」
  看到的是「这个分类下还没有关卡」。现在区分「猜的页」与「选的页」（`pagePickedByUser`），
  刷新只允许覆盖前者；`LevelPage.firstRealPage` 让"第一页"指第一页**有内容的**页。
  回归用例：`LevelSelectLandingPageTest`（含"玩家选的桶页要在刷新后留住"）。

---

## 10. 扩展点：想加东西该接哪

| 想加什么 | 接哪 | 现状 |
|---|---|---|
| 新界面 | 继承 `Screen`，`addWidget` / `showDialog`，自己的点击区写 `onMouseClicked` | ✅ 现成 |
| 新覆盖层 | 继承 `Overlay` + 在 `PvzceClient` 给一个打开入口 | ✅ 现成（帧循环不用改） |
| 屏内的确认框/输入框 | `Dialog` + `Screen.showDialog` | ✅ 现成 |
| 需要"离开时释放"的东西 | 覆写 `Screen.onRemoved()` | ✅ 现成 |
| 新前置流程（选难度、选阵营） | 仍要改 `enterLevelFromMenu` 的三分支 | ⚠️ 待做 |
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
   界面只负责"我选中了哪一关"，不要自己判断有没有存档、要不要选卡。
4. **"有没有存档" 与 "通关了没有" 是两个字段**（`LevelInfo.runningSave` 与 `status`），不要合并。
5. **进关卡只发一个包**：`ContinueLevelC2S` / `PlayLevelC2S` / `RestartLevelC2S` 三选一，
   不要混用，也不要再给它们加"这次算不算重开"的布尔。
6. **丢本关客户端状态 → `clearLevelClientState()`**，不要各自清一半。
7. **进入游戏的那一步在 `PvzceClient.onLevelInit`**，界面不要自己 `setScreenReplacing(InGameScreen)`。
8. **关卡开场对话的宿主取决于这一局怎么进的**：走选卡的关卡在 `ChooseSeedsScreen` 播，
   直接进关卡的（传送带）在 `InGameScreen` 播，由 `directDialogueLevelId` 跨往返携带这个决定。
9. **背景 cover 适配 → `Screen.coverFit`**，按背景图像素写死的面板矩形要经它映射成点击区域。
10. **离开一屏要去哪 → `Screen.backTarget()`**。不要读 `screenDepth()` 来推断，也不要覆写
    `requestClose()` 去调用别的屏的展示方法：`POP` 是默认，只有"客户端把这一屏当作流程根"时才
    用 `replaceRoot`。
11. **离开时要释放的东西 → `Screen.onRemoved()`**，不要写进各自的出口方法。
12. **浮在别的屏上面的界面 → `Overlay`**，不要压进屏幕栈：栈深度是"嵌套了几屏"，不是"屏 + 浮层"。
