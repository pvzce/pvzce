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

有限阶段关卡在同一块棋盘继续：`RoundClearS2C` → `RoundClearDialog` → push `ChooseSeedsScreen`，
确认发送带阶段号的 `StageChoiceC2S`，收到非 choosing 的阶段同步后 pop 回原 `InGameScreen`。
不重新发送关卡初始化，不替换场景、实体和资源；增益随阶段状态更新，准备阶段供玩家修整。

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
render();                        // screens.render()：初始化实际栈顶后绘制，然后 overlay.render()
```

`ScreenStack.render` 在实际绘制前再次调用当前屏的 `initIfNeeded`：tick 内切屏时，新页面在同一帧先初始化再绘制，
不额外 tick；没有切屏时初始化入口保持幂等。页面初始化由绘制入口统一保证，具体页面直接使用初始化好的布局。

覆盖层与它下面的屏**互不干扰**：屏照常 `tick()`、照常 `render()`，覆盖层只是画在它上面并且
独占输入。这在结构上成立，不再需要"如果是 ConsoleScreen 就特殊照顾"的分支。

仅在已加载关卡、屏幕栈保留 `InGameScreen` 的光影设置页，右下角显示光影预览按钮。
`PvzceClient.previewAvailable` 限定入口；预览属于显示模式，不压栈或改变暂停状态。
`ScreenStack.find` 查找仍存活的 `InGameScreen`，预览时调用其 `renderLightingPreview` 只画世界；
当前屏、对话与覆盖层保持原状态，显示和输入暂停，右下角按钮与截图键可用，Esc 退出预览。
换屏和离关取消预览；退出后恢复原页面的模糊背景及界面。
关卡界面通过绑定动作 `HIDE_UI`（默认 F1）隐藏/恢复 UI；隐藏时直接绘制同一世界，保留截图键，F1 或 Esc 恢复。
该模式不显示预览按钮，不改变模拟或暂停状态；隐藏控件不接收输入，换屏或离关取消隐藏。

---

## 3. 屏幕栈

### 3.1 API 只有四个方法

```java
setScreenReplacing(screen)   // replace —— "从现在起是另一段流程"；被丢下的屏收到 onRemoved()
openScreen(screen)           // push    —— "这一屏盖在上一屏上，返回要回来"
navigateBack()               // back    —— 离开当前屏，去哪由 Screen.backTarget() 说了算
popScreen()                  // 无处可弹时不动，留给"客户端自己收回一屏"的场景
currentScreen() / screenDepth()   // peek / 导航深度（覆盖层不计入）
```

`ScreenStack.push` 在压入子页前调用原顶屏的 `Screen.onHidden`；`suspend` 在悬置栈前调用当前屏同一钩子。
它用于停止试听这类只属于当前页的活动，页面本身保留，返回时不会自动重新播放；移除资源仍由 `onRemoved` 释放。

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
| 嵌套 | `openScreen` | `Title→LevelSelect`（开始游戏）、`Title→Shop/Packs`（钥匙牌 / 数据包墓碑）、`LevelSelect→LevelSetup`、`LevelSelect→Almanac`、**`LevelSelect→LevelCollectionScreen`（点一个关卡集合）**、`Settings→Config/Video/LightingSettingsScreen`、`InGame→Award`、`openEditor`、`ChooseSeedsScreen` |
| 声明的目的地 | `Navigation.replaceRoot(...)` | `LevelSelectScreen.backTarget()`（无下层时）、`AwardScreen.backTarget()` |
| 弹回 | `Navigation.POP`（默认） | 返回屏幕的按钮、`Screen.requestClose()` 默认实现；弹窗的 Esc 只关闭弹窗 |

### 3.3 导航状态图

```
TitleScreen
 ├ 开始游戏 → LevelSelectScreen → LevelSetupScreen / ChooseSeedsScreen → InGameScreen
 ├ 创意工坊 → WorkshopScreen（工作区列表）→ WorkshopScreen（工作区内容）
 │                                          ├ WorkshopPackScreen（名称与图标设置）
 │                                          ├ EditorScreen（关卡）
 │                                          ├ WorkshopJsonScreen（分组面板、能力卡、动画帧、翻译）
 │                                          └ WorkshopPreviewScreen（素材预览/试听）
 ├ 模组墓碑 → ModsScreen
 ├ 数据包墓碑 → PackScreen
 ├ 设置花盆 → SettingsScreen → ConfigScreen / VideoSettings / LightingSettingsScreen / KeybindScreen / AiSettingsScreen / AccountScreen
 ├ 商店钥匙牌 → ShopScreen
 └ 玩家木牌 / 切换玩家 → PlayerPickerDialog（名单）
                            ├ 新玩家 → 独立创建弹窗 → 名单高亮新玩家
                            ├ 删除选中 → ConfirmDialog → 确认删除 / 取消
                            └ 确定 → 切换玩家并关闭；取消 → 保持当前玩家

光影页也可从视频页打开，完成或 Esc 弹回原入口；修改即时保存，不更换流程根。

账号页沿屏幕栈返回原设置页，退出账号只切离线，不切换本地玩家或离开关卡；账号网络任务不参与导航等待。

对话预览：EditorScreen / WorkshopJsonScreen → DialoguePreviewScreen → 返回原编辑屏幕（不保存、不构建）
工坊测试：悬置原屏幕栈 → WorkshopWaitingScreen → 选卡 / InGameScreen
退出测试：服务端恢复确认 → 恢复原屏幕栈与编辑位置
普通关卡：ESC 打开 PauseDialog；领奖进入 AwardScreen；离开回到关卡列表
```

> 控制台（`ConsoleOverlay`）与聊天行（`ChatOverlay`）不在图里：它们是覆盖层（§8），
> `/` 与 `T` 分别打开，都不参与导航。

---

## 4. 进入关卡

### 4.1 决策只有一处，但入口有五个

**唯一决策函数**：`PvzceClient.enterLevelFromMenu(info)`

```java
if (info.hasRunningSave())        requestLevel(id, false);        // ① 有存档：直接进，服务端弹框
else if (asksForTeamChoice(info, pendingHumanTeam))
                                  openScreen(LevelSetupScreen);   // ② 关卡有两方以上可玩、且还没答过：先问阵营
else if (skipsSeedScreen(info))   startLevelWithSeedsAndBuffs(…);  // ③ 关卡说它没有选卡这一问：直接开局
else                              openSeedSelection(info, false); // ④ 其余：选卡页
```

**③ 是关卡自己说的话**（`LevelDef.seedScreen`，JSON 里写 `"seed_screen": false`；哪些关卡写了这个键由各自的关卡 JSON 决定 —— 名单不在这里维护），
判据 `PvzceClient.skipsSeedScreen(info)` = 关卡声明了不弹 **且** 它没有可挑的增益页（有增益页就还得开，
那一页是真正的问题）。**它不与"固定卡组"划等号**：1-1 也固定卡，但它要那一屏 —— 它要在那里播开场对话。
反过来说，**固定卡组 + 不播对话的关卡必须自己声明这一句**（`seed_screen` 默认 true，没写就等于要那一屏）。服务端配合的一条：没有选卡页的关卡收到空的选择请求时按
"没人问过"处理（`SeedSelection.plan`），于是卡组是默认卡组而不是"只有固定卡"。

**④ 里的"选卡页"有两种形态，由关卡决定**：有卡可选就是真正的选卡；卡组没得选（关卡的固定卡填满卡槽，
或者这一关的卡由它自己发 —— 传送带）就是**仅预览过场**（`ChooseSeedsScreen.previewOnly`：不画面板、不画卡池、
点击直接落到草坪上，播完开场对话后 2.1 秒自动开局）。**传送带关卡也走这一屏**（只是没得选）：关卡列表与选卡页之外，没有第三处显示僵尸预览。

**阵营是关卡声明的**（`LevelDef.playable_teams` → 每条 `TeamInfo.playable`），所以"要不要问"是关卡数据
的回答而不是客户端的猜测：**只有一方可玩时 ② 整条分支不成立**，内置关卡因此从列表点进去就是选卡页/开局。
判据是 `PvzceClient.asksForTeamChoice(info, pendingHumanTeam)`（纯函数）：`offersTeamChoice(info)` 只回答
"这关有几方可玩"（恰好一方可玩就不是一个问题），`asksForTeamChoice` 再叠一层"玩家是否已经答过"——
少了后一半，关卡准备页的「开始游戏」会把自己刚答过的那个问题再问一遍。

| # | 入口 | 位置 | 走的路 |
|---|---|---|---|
| 1 | 关卡列表「继续游戏/下一步」 | `LevelSelectScreen.openSetup()` | 转发给 `enterLevelFromMenu`（与②③④同一决策） |
| 2 | 关卡准备「开始游戏」 | `LevelSetupScreen.startGame()` | 转发给 `enterLevelFromMenu`（同一决策，第二份调用） |
| 3 | 存档提示框「重新开始」 | `PvzceClient.openSeedSelectionForRestart` | 打开选卡界面（正常存档返回提示框，加载失败则返回列表）；取不到关卡信息、或这一关声明了 `seed_screen: false` 时回落到 `RestartLevelC2S`（重开不是一次选卡） |
| 4 | 暂停菜单「重新开始」 | `PvzceClient.restartCurrentLevel()` | `LeaveLevelC2S` → 清状态 → 选卡页（`onBack = showLevelList`；没得选的关卡是仅预览过场，会自己开始） |
| 5 | 编辑器「测试」 | `PvzceClient.testWorkspace()` | 保存草稿 → 临时会话确认 → 新关卡列表 → 选卡；退出恢复编辑屏幕栈 |

> 把关卡列表那一屏的分支收进 `enterLevelFromMenu` 是必要的，不只是整洁：入口 5 与冒烟钩子都直接
> 调它，列表里那份判断它们走不到 —— 一个"有两方以上可玩"的关卡从编辑器进去会跳过阵营选择。

### 4.1.1 联机入场

`TitleScreen` 的多人入口打开 `MultiplayerScreen`，未连接时展示已保存服务器、自动扫描局域网游戏及创建房间入口。
直接连接和添加/编辑服务器压入 `ServerEntryScreen`，取消弹回原列表；保存成功更新原列表的选择，直接连接使用既有加入流程。
扫描器归多人页面所有，子表单期间保留，移除页面和关闭屏幕栈时释放；窗口缩放保留表单内容。
合作房间的房主走原关卡列表和选卡页，提交卡组回到大厅；有进行中存档的新局先走重开确认。
合作客人完成内容同步后，点击“选卡并准备”请求当前房间的关卡数据，响应到达后自动打开选卡页，无需再次点击。
房主切关取消旧准备请求并关闭旧选卡页；同一关卡的名单更新保留选卡操作。公共增益和阶段推进归房主；真人对战房主只看到对战地图，在阵营页确认并准备；客人点击“准备对战”加入相反阵营，使用地图卡组，不经过选卡。
大厅依据服务端模式、阵营、个人准备和可开始字段渲染，开始按钮等待双方在线且准备；选图取消弹回大厅，独立根列表返回房间。
全员准备后房主开始，收到各自的 `LevelInitS2C` 才整栈替换进入战场；大厅控制由 `RoomC2S` / `RoomS2C` 承载。

加入远程房间前先等待本地服完成存档并暂停的确认，联网和素材指纹计算在后台执行。连接中返回会取消连接并恢复本地服，迟到的暂停确认不再发起联网。
断线转回多人页显示原因并允许同地址重连；离开恢复本地内容和连接，再打开本地菜单。
个人菜单不暂停公共世界。下面的三个入场包描述单机语义，联机中由房间解释为选关或准备，客人不能指定房主世界或执行整关退出。

### 4.2 三个包，三套语义

| 包 | 谁发 | 语义 |
|---|---|---|
| `ContinueLevelC2S(levelId, world)` | 关卡列表的「继续游戏」/ 存档提示框的「继续」/ 编辑器测试与冒烟（`restart=false`） | "**加载我保存的那一局**"。不带卡组：卡槽与阵营都在存档里 |
| `PlayLevelC2S(levelId, world, restart, seeds)` | 选卡界面的「开始游戏」 | "**用这套卡组开一局**"。从列表来（`restart=false`）时有存档就加载并询问；从存档提示框的「重新开始」来（`restart=true`）则丢弃存档 |
| `RestartLevelC2S(levelId, world, seeds)` | 暂停菜单的「重新开始」/ 冒烟（`restart=true`） | "**丢掉存档，从头开始**"，不需要选卡界面 |

**这三个类型是分开的，不是一个包加一个 `restart` 布尔**：布尔本身就等于整条消息，所以「继续 / 重开 / 用这套卡开一局」各自是一种包类型，不需要在旁边再记一个"玩家已经表过态"的字段。

**服务端 `createLevel(levelId, world, intent, requestedSeeds)`**（`PvzceServer.java`）对三者统一处理，
`intent` 由**包的类型**翻译而来 —— "玩家是否已经表过态"就是包的类型本身：

```java
// A save is a run to resume unless the player has already said no to it.
boolean loadSave = (intent == CONTINUE || intent == PLAY) && hasSave;
if (loadedSave) {
    savePromptPending = true;                 // 冻住关卡 tick（run() 里 !savePromptPending && !manualPause）
    connection.send(new LevelSavePromptS2C(...));  // 存档已经加载并渲染在背后，然后才问
}
```

四种 intent 各自的行为：

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
2. **正常存档提示框在 `LevelInitS2C` 之后发**，即"先把存档加载并渲染出来，再问要不要它"。
   客户端在 `onLevelInit` 里补开提示框（`showLevelSavePrompt` 若当前不是 `InGameScreen` 就先存进
   `deferredSavePrompt`）。加载失败时只有带失败标记的提示包，客户端直接在关卡列表弹框，
   清除延后的提示与开场对话标记，不等待初始化；返回保留存档，确认重开才清除本局进度（服务端边界见 `架构-服务端.md` §4.3）。
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
| 暂停「继续游戏」 | `PauseDialog` 的「继续游戏」行 | `close()` → `PauseGameC2S(false)` | 留在 `InGameScreen` |
| 暂停「重新开始」 | `PauseDialog` 的「重新开始」行 | 先弹 `ConfirmDialog`，确认后 `LeaveLevelC2S` → `clearLevelClientState()` → 选卡页（没得选的关卡是仅预览过场） | `ChooseSeedsScreen`（`onBack = showLevelList`） |
| 暂停「保存并退出」 | `PauseDialog` 的「保存并退出」行 | `close()` → `leaveLevel()` = `LeaveLevelC2S` + 清状态 | `TitleScreen`（玩家列表） |
| 胜利 + 奖励 | `InGameScreen.showReward` → 点击领取 | 播胜利音乐 → `openAwardScreen()`（push） | `AwardScreen` |
| 奖励页「继续」 | `AwardScreen` 按钮 / `requestClose()` | `finishLevelAndShowList()` | `LevelSelectScreen` |
| 失败 | 点击任意处 | `finishLevelAndShowList()` | `LevelSelectScreen` |

**`clearLevelClientState()` 是唯一一份"丢掉本关所有客户端状态"**：
`savePromptOpen` / `deferredSavePrompt` / `directDialogueLevelId` / 音乐 / 粒子 / 涟漪 / 动画 / `level.reset()`。
`leaveLevel()`、`restartCurrentLevel()`、`finishLevelAndShowList()` 都调它 —— 不允许各自清一半。

**`restartCurrentLevel()` 的顺序有意义**：

```
LeaveLevelC2S  →  服务端 leaveLevel() 先存档再 shutdown（放弃重开也不丢进度，列表里仍是"进行中"）
clearLevelClientState()
setScreenReplacing(选卡)  ← 不是 push：旧关卡不会以冻结实例留在栈下面
```


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
**约束：界面自己判定点击范围时不要越过控件。** 界面若先判定自己那块占满窗口的区域再委托，对话框的按钮就全部点不动；`mouseClicked` 是 `final`，模态分发先过它，结构上不可能再绕过。

`Dialog` 是**屏内**的模态层，不是新屏：

- `InGameScreen` 的暂停菜单是 `PauseDialog`（`Dialog`），不是 `PauseScreen` —— 棋盘保持可见；
- `DialogueOverlay`（关卡开场对话）也是 `Dialog`，所以"对话期间选卡与按钮点不动"是结构上成立的；
- `Screen.onResize()` 会重建基础 widget 并把已打开的对话框按新尺寸放回（对话框不会因改窗口而消失），
  靠"字段里已存在的 dialog 不再重复 add"这条隐式合约。

---

## 7. 输入层：谁处理 ESC

`PvzceClient.pollInput()` 将普通屏幕的 Esc 交给共用分发入口，游戏内与关卡编辑器保留各自处理：

```java
if (overlay != null) {                 // 覆盖层开着时，键盘整个归它
    overlay.keyPressed(key);
} else if (key == GLFW_KEY_ESCAPE
        && !(screen instanceof InGameScreen)
        && !(screen instanceof EditorScreen)) {
    screen.dispatchEscape();           // 弹窗优先，然后使用当前屏的关闭策略
}
```

`InGameScreen` 与 `EditorScreen` 自己处理 ESC（游戏内要开暂停框、编辑器按页处理）；
其余界面经 `Screen.dispatchEscape` 先把按键交给顶层模态弹窗，关闭时移除弹窗并释放焦点；
没有弹窗再走 `requestClose()`，默认实现就是 `client.navigateBack()`，
`AwardScreen` / `ChooseSeedsScreen` / `LevelSelectScreen` 各自覆写。
`WorkshopJsonScreen` 在子配置中返回进入前的位置，恢复原分组与页码；顶层退出保存草稿，
无效数字保留在编辑器中等待修正。「保存并返回」可从子配置直接退出。

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

**为什么不能压栈**：

1. `screenDepth()` 会被污染 —— 控制台开着时深度多 1，任何按深度判断的界面都会读错；
2. 帧循环要在两处特判它，才能让下面的屏继续 `tick`/`render`；
3. 再加第二个覆盖层就要再加两处特判。

现在加一个覆盖层 = 写一个 `Overlay` 子类 + 在 `PvzceClient` 里给它一个打开入口，
帧循环一行都不用改 —— 聊天行是第二个实现，`PvzceClient` 里只多了
`openChat()` 一个入口和"哪种浮层用哪个键"的两个分支。

> 两者的分工是**键与形状**，不是同一件的两份：控制台是一个面板（工具，开着待一会儿），
> 聊天行是屏幕底部的一条（说一句就忘）。它们画的是同一份消息表（`ClientLevel.messages()`），
> 因为服务端说的话只有一份。

---

## 9. 这一层的现状

三层切换的机制本身没有待修的已知不一致：`POP` 是默认、无处可弹会报错并留在原地；`replaceRoot` 只在
"客户端把这一屏当作流程根"时用；控制台是独立的 overlay 槽位；屏离开栈时 `onRemoved()` 一定被通知一次；
关卡列表区分「猜的页」与「选的页」（`pagePickedByUser`），刷新只覆盖前者。

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
| 标题页再添一个入口 | `TitleScreen` 的入口定义、共用画布映射与贴图按钮 | ✅ 绘制与命中共用控件矩形，墓碑过滤倾斜面的透明角 |
| 关卡对话里的玩家名 | 台词里写 `${user_name}`，替换在 `DialogueOverlay.create` | ✅ 见 `架构-客户端.md` §6.2.2 |
| 关卡自带的开场对话 | 关卡 JSON 的 `dialogue` 块，宿主是选卡页或游戏内 | ✅ 数据驱动 |
| 新的"自供卡组"机制 | `common/level/mechanic` + `ClientMechanics` 路由 | ✅ 注册制 |
| 新关卡分类页 | `level_theme` / `level_category` 注册表 + id 路径 | ✅ 数据驱动 |

**关卡入场的扩展瓶颈**：加一屏前置流程 = 改 5 个入口里的分支 + 3 个跨往返字段
（`directDialogueLevelId` / `deferredSavePrompt` / `savePromptOpen`），
因为它们的状态没有一个统一的归属 —— 这是已知未做的一条，见 `todo.md` 的「关卡的入场状态没有统一的归属」。
协议那一半已经收口了（见 §4.2）。

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


### 工坊测试的栈生命周期

`ScreenStack.suspend` 暂存原屏幕对象，不触发离开回调；测试场景可正常替换自身根屏。
`ScreenStack.restore` 释放测试屏，再恢复原栈并按当前窗口尺寸重建布局。
`PvzceClient.showLevelList`、离开关卡与完成关卡入口在测试期间均结束临时会话，取消选卡也走这一条路。
工作区素材预览保留前一个素材覆盖层，关闭预览后返回所属编辑器的覆盖层。
