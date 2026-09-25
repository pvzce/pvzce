# 批次 1：9 类已报告缺陷

> 根因与行号都来自动工前的代码盘点（2026-09）。行号是盘点当时的，改之前先按符号名复核一次。
> 修完每一类都按 `09-验证与提交纪律.md` 的对应档位验一次；本批可拆 2~3 次提交。

---

## 缺陷 1：后来的随机卡槽被先前的传送带覆盖

**用户原话**："后来的随机卡槽也会被之前的传送带覆盖，后来的随机卡槽应该能覆盖前面的传送带，
始终是后来的覆盖前面的。"

### 根因（两条独立的路径，都要修）

**路径 A：优先级压过先后顺序。**

- `MutationManager.recompute`（`common/level/mutation/MutationManager.java:621-672`）先算
  `highestPrecedence = max(cardSourcePrecedence())`，再看 `precedence < highestPrecedence → continue`。
- `ConveyorMutation.cardSourcePrecedence()` 是 **10**（`ConveyorMutation.java:52-55`）；
  `SlotReplaceMutation` 不覆写 → **0**，但它额外写了一条**与顺序无关**的压制规则
  （`SlotReplaceMutation.java:48-51`）：`return other instanceof CardDealingMutation;`。
- 于是"先传送带、后卡槽替换"时，卡槽替换永远进不了 acting 集，而且
  `evictOldest`（`:587-596`）拒绝驱逐当前持有卡槽的条目，传送带永不退场 → 永久压制。

**路径 B：卡槽重建把刚写好的卡覆盖回去。**

- 当传送带在同一趟 `recompute` 里离场、而 `slot_replace` 被重新 apply 时：
  `toApply` 在 `:654-659` 先跑（卡槽被改写），随后 `cardSourceFactory` 由 belt → null，
  `:669-671` 触发 `onMutationCardSourceChanged()` → `LevelServer.rebuildCardBar()`
  （`server/level/LevelServer.java:2438-2458`）→ 没有 factory → `DeckCardSource`
  构造函数 `replaceSlots(PvzcePlayer.deckSlots(selectedCards))`（`DeckCardSource.java:36-41`）
  → **用关卡开局那套卡覆盖掉随机卡**。

### 修法

1. `Mutation.cardSourcePrecedence()` 保留"谁能当发牌者"的含义，但**胜负改由到达顺序决定**：
   在 `recompute` 里按 `entries` 的下标走，**最后一个能发牌的条目赢**（`newOwner = entry` 不再比较数值）。
   数值只保留"0 表示不发牌"这一个含义，删除 `highestPrecedence` 这个变量。
2. `isHeldBack` 带上顺序：只有**更晚**到达的发牌者才压制**更早**的。签名改成
   `isHeldBack(entry, index)`，并让 `Mutation.suppressedBy(Mutation other)` 保留给真正的
   "非发牌者也被压制"的例外（`SlotReplaceMutation` 那条），但**同样加上顺序**：
   `other instanceof CardDealingMutation && other 更晚`。
3. `rebuildCardBar()` 在"没有 factory"时要问一句"现在还有没有别的变异在管卡槽"：
   让 `MutationManager` 暴露一个 `reapplyCardBarOwner()`，或者更简单——
   `rebuildCardBar` 之后立刻把 `applied` 里所有改写卡槽的变异按到达顺序重放一次。
   **采用后者**：`LevelServer.rebuildCardBar()` 末尾调用
   `mutations.reapplyBarMutations()`，它遍历 `entries`（到达顺序）对所有
   `implements BarRewriting` 的已应用条目调一次 `reapplyBar(level)`。
   `SlotReplaceMutation` 实现 `BarRewriting`，其 `reapplyBar` 就是把 `Applied.cards` 再写一次。
4. 更新被旧语义钉住的测试：
   - `common/level/mutation/MutationManagerTest.java:187`（`assertTrue(slotReplace.suppressedBy(conveyor))`）
   - `MutationManagerTest.java:202-217`（`theBeltSuppressesTheBarRewriteAndNothingElse`）
   - `MutationManagerTest.java:336`（`theBeltReplacesThePlantCardsAndKeepsTheToolsAndResources`，
     它的断言现在名不副实，见缺陷 2）
5. **加一条回归用例**：先 `add(conveyor)`、后 `add(slot_replace)`，断言"卡槽是替换后的那套"，
   而且**反过来**（先替换、后传送带）时传送带赢。按 `踩坑清单.md` 第 32 条，这种全目录规则要
   遍历着测：对每一对"会碰卡槽的变异"都跑一遍顺序断言。

---

## 缺陷 2：变成传送带后必须保留选择的工具

**用户原话**："变为传送带后，应该要保留选择的工具。"

### 根因（两处）

1. **服务端把工具丢了。** `ConveyorMutation.beltFor` 的文档写着"只有植物卡被替换、
   工具与资源卡原样留着"（`ConveyorMutation.java:65-73`），但
   `BeltCardSource.rebuildBar()`（`server/level/cardsource/BeltCardSource.java:132-146`）
   是 `player.replaceSlots(rebuilt)`，`rebuilt` **只有传送带卡** → 铲子/手套/水壶/资源卡全部消失。
2. **客户端把选中项丢了。** `InGameScreen.cardBar()`（`client/gui/screens/InGameScreen.java:2390-2397`）
   在 `cardBarKind` 变化时执行 `cardBar = null; selectedCard = -1;`。

### 修法

1. `BeltCardSource` 在构造时记下**非植物卡的槽位**（`player.slots()` 里 `kind != PLANT` 的那些，
   保持它们的 `Slot` 原样、含冷却与剩余次数），`rebuildBar()` 先排传送带卡、
   再把它们追加在后面。**传送带卡的 `index` 仍用 `card.id()`**（`take(index)` 按 id 找），
   所以追加在后面不会错位。
2. 客户端：`InGameScreen` 在丢掉 `cardBar` 之前，把当前选中槽的 `defId` 记下来，
   新卡槽到位后按 `defId` **重新找回下标**（同一份 `ClientLevel.slots()` 里 `defId` 唯一）。
   这段逻辑与既有的 `syncCarry`/`gloveInBar`（`:1758-1785`）是同一套做法，复用它。
3. 如果新卡槽里**没有**那个 `defId`（真的被替换掉了），才清空选择。
4. 回归用例：`MutationManagerTest` 里那条名不副实的用例改成真的断言
   "传送带条的末尾仍有铲子卡"；客户端那一半用 `ClientHarness` 断言
   "换条之后 `selectedCardIndex()` 指向的新槽 `defId` 与换之前相同"。

---

## 缺陷 3：爆炸的光效与伤害上限

**用户原话**："僵尸爆炸/植物爆炸不要产生光效，太碍眼了；僵尸爆炸的伤害最大只能杀死普通僵尸，
植物爆炸的伤害只能使豌豆血量掉一半的伤害，防止连环炸。"

### 现状（盘点结论）

- **没有僵尸爆炸的内容**：没有 `jack_in_the_box` 僵尸定义、没有僵尸侧爆炸能力；
  只剩素材（`assets/pvzce/sounds.json` 的 `jack_explode_*`、3 个 `data/pvzce/particles/explosion/jack_explode_*.json`、
  `refer/anim/Zombie_jackbox.reanim`）。用户说的"僵尸爆炸"就是**变异 `zombie_blast`**。
- 两条爆炸变异共用一个实现：`common/level/mutation/MutantBlast.java`
  （`RADIUS_CELLS = 0.5`、`DAMAGE = 1800`、`pvzce:ash`、粒子 `POTATO_MINE_FLASH`）。
- **没有任何爆炸发点光**：`RenderSystem.setPointLight` 全项目只有一个调用点
  （阳光掉落，`client/PvzceClient.java:682`）。"光效"是**叠加混合的粒子**：
  `pow_flash`（`"additive": true`）、`doom_doom_flash`、`boss_explosion_flash`、
  `potato_mine_flash`（后三个不是 additive 但都是白色/橙色的大闪光）。

### 修法

1. **去光效**：把 `MutantBlast` 的粒子换成**不带闪光**的那一个（用 `EXPLOSION_POW`，它不 additive、
   也没有 flash 兄弟），并把 `pow_flash` / `potato_mine_flash` / `doom_doom_flash` 从
   **这两条变异**的粒子表里去掉。判定标准是"画面上不再有整屏发白的一瞬"，
   用 `captureEvery` 拍帧带核对。
2. **僵尸爆炸的伤害上限**：上限 = **普通僵尸的血量**，从数据读（
   `BuiltInRegistries.ZOMBIES.get(pvzce:basic_zombie).health()`），不写死 200。
   插入点在**唯一的僵尸受击入口** `ZombieEntity.damage(int, DamageTypeDef, LevelAccess)`
   （`server/entity/ZombieEntity.java:616`）。
   做法：给该方法加一个**爆破专用的重载**，或者在 `MutantBlast` 里 `Math.min(damage, basicHp)`
   —— **选后者**（`MutantBlast` 是唯一调用点，改了不影响别的伤害源；放在实体里会让
   `damage()` 多一个"这次算不算爆破"的参数，把简单的事弄复杂）。
   注意 `MutantBlast` 的友军伤害是 `zombie.damage(DAMAGE, …)`（`:59`）单独走的，
   两处都要取上限。
3. **植物爆炸的伤害上限**：上限 = **豌豆射手血量的一半**（`pea_shooter.health / 2 = 150`），
   同样从数据读。插入点：`MutantBlast.java:65` 的 `plant.damage(DAMAGE)` ——
   *注意它绕过了 `PlantEntity.damageFrom`/`isInvulnerable`*，所以上限要加在
   **`PvzceEntity.damage(int)`**（`server/entity/PvzceEntity.java:61`）这一层，
   或者干脆也在 `MutantBlast` 里 `Math.min`。**选 `MutantBlast` 内联**，
   同时把这个"绕过 `isInvulnerable`"的现状写进注释（它是有意的：正在引信上的炸弹也该被殉爆炸掉，
   否则连环炸会停在引信上）。
4. 上限的**数值来源**加一条测试：`MutantBlastTest`（新）断言
   "伤害上限读的是注册表里的普通僵尸血量 / 豌豆射手血量的一半"，
   这样以后调平衡改了血量，上限跟着走（`踩坑清单.md` 第 31 条：不要留一个会对内容失效的缺省）。

---

## 缺陷 4：非水生僵尸刷新在水面上，刚出现就落水

**用户原话**："很多非水生僵尸会在水面行刷新，导致刚出现就落水死了。"

### 根因（链条，已逐环确认）

1. `WaveDirector` 每只僵尸都生成在 `x = host.width() + 0.6F`（`server/level/WaveDirector.java:749-752`），
   行号来自 `QueuedZombie.rowFor`（`:829-832`）遍历 `shuffledRows()`（`:835-842`）——
   **这个列表包含全部行，没有任何地形过滤**。
2. `x = 9.6` 在棋盘外，而 `Entity.gridX()` **夹到最后一列**
   （`api/entity/Entity.java:162-173`）→ 实际读的是第 8 列。
3. `ZombieEntity.tick` 的第一件事就是 `drownInWater`（`server/entity/ZombieEntity.java:257`），
   它读 `level.sceneAt(gridX(), gridY())`，命中 `#c:water` 且 `def.canSwim()` 为假就 `remove()`。
4. 泳池关的 2/3 行**每一列都是水**，所以第 8 列也是水 → 生成当帧就死。
5. **现成的复现数据**：`data/pvzce/levels/yard/adventure/combat_test.json` ——
   它是水池棋盘，却有 10 条 `entries` 没写 `rows`。

### 修法（两层，都要）

1. **中央兜底（必须）**：`LevelServer.spawnZombie(...)`（`server/level/LevelServer.java:1140`）
   在造实体之前判断"这一格对这个僵尸合法吗"：`def.canSwim()` 为真 → 水行合法；
   为假 → 只在非水行合法。不合法就把 `row` **改到最近的合法行**（不是"拒绝生成"，
   拒绝会让波次卡住），并打一行 debug 日志。这一层同时修好另外 4 个行盲的生成点：
   `ZombieCrisisMutation.java:101`、`BossPhasesCapability.java:72`、
   `SummonDancersCapability.java:270`、`HammerCapability.java:115`。
2. **选行时就选对（应该做）**：`WaveDirector` 在关卡创建时把行分成 `landRows` / `waterRows`
   （这份切分已经存在：`LevelServer.java:595-625` 与 `EndlessWaves.Rows`），
   按 `ZombieDef.canSwim()` 选池子。这样僵尸不会"先被扔到水里再被挪走"，
   落脚点是作者写的那个。
3. `rows` 显式写了水行、而僵尸不会游泳时：**尊重作者的意图但改为邻行**，并记一条
   warn —— 因为 `WaveDef.Entry.rows` 的文档（`api/content/WaveDef.java:152-163`）已经说
   "陆行僵尸送进水里是错的"，这属于关卡数据错误。
4. 顺手给 `combat_test.json` 补上 `rows`（它是测试关，但它是唯一的数据级复现）。
5. 回归用例：`PoolMechanicsTest` 已有 `anEntryOnlySpawnsInTheLanesItNames`；
   新增一条"**没写 rows 的陆地僵尸在泳池关里也会落到陆地行**"（遍历 5 条泳池关 × 全部非水生僵尸）。

---

## 缺陷 5：三线射手攻击动画诡异

**用户原话**："三线射手的动画存在问题，在攻击时动画很诡异。"

### 根因（三个，按影响排序）

1. **`shoot` 把 9 根叶/茎骨头重置到 scale 1.0、位置也偏掉。**
   `assets/pvzce/animations/plant/attacker/threepeater.json` 的 `shoot` 里，
   `peashooter_backleaf` 等骨头只有 1 个关键帧、`scale [1.0, 1.0]`、`translation [-0.248, 0.862]`
   （如 `:3994`），而 `idle`（`:781`）里同一根骨头有 25 个关键帧、`scale ≈ 0.555`、
   位置在 `[-0.022, 0.256]`。同族三个攻击手（`pea_shooter` / `repeater` / `gatling_pea`）
   的 shoot 里这些骨头**保持 0.555**。所以只有三线射手在开火瞬间叶茎被放大 1.8 倍、
   外移 3.4 倍，打完再弹回来。
2. **三个头用布尔 visible 硬切、没有过渡**：`head` `{0.0:true, 1.083:false}`（`:4386`）、
   `head_2` `{0.0:false, 1.083:true, 2.167:false}`（`:5418`）、
   `head_3` `{0.0:false, 2.167:true}`（`:6304`）。`idle` 里三个头是同时在的。
3. **片长比开火周期长**：`shoot` 是 `3.25s / rate 2.0 = 1.625s`，
   而 `ShooterCapability` 的 `interval` 是 **90 tick = 1.5s**（`data/pvzce/plants/threepeater.json`），
   所以每次都播不完就重来，中间还夹一帧 `idle` 触发 `transition 0.1` 的 100ms 交叉淡入。

### 修法

1. **转换器层面修**：`tools/reanim_to_pvzce.py` 导出 shoot 时，对"在 `idle` 里被动画、
   在 `shoot` 里只有一个静止关键帧"的骨头，**用 idle 第 0 帧的 pose 兜底**，而不是原样抄
   reanim 里的初值。这是**通用修复**（其它植物只是碰巧没踩到），改完要重跑
   `reanim_to_pvzce_all.py` 并核对 diff **只**动了三线射手这一类骨头。
2. 三个头的 visible 硬切保留（原版就是逐个头开火），但把**时长与开火节奏对齐**：
   `data/pvzce/plants/threepeater.json` 的 `interval` 90 → **97**（1.625s，
   与 `3.25 / 2.0` 一致），或把 clip 的 `rate` 调成 `3.25 / 1.5 = 2.1667`。
   **选后者**：动画适配开火节奏，而不是让数值去迁就美术（数值是玩法，美术是表现）。
3. 加一条测试钉住"**每个 shoot 片段里，凡是在 idle 里被动画过的骨头，在 shoot 里的 scale
   不能与 idle 第 0 帧相差超过 1%"——这属于 `AnimationResourceLoaderTest` / 新增
   `AnimationClipConsistencyTest` 的活，一次覆盖全部植物。
4. 验收：`.smoke/` 拍一条帧带（三线射手连开 3 发），确认叶茎不再爆开、三个头依次开火、
   没有中途回 idle 的跳变。

---

## 缺陷 6：倭瓜应该压扁僵尸，不是爆炸

**用户原话**："倭瓜攻击错了，倭瓜攻击时应该是压扁僵尸，而不是现在的爆炸。"

### 现状

`data/pvzce/plants/squash.json` 用的是 `pvzce:explosive`（`trigger: proximity`、
`fuse_ticks: 60`、`radius: 1.0`、`square: true`、`damage: 1800`、`linger_ticks: 40`），
打的是 `level.damageArea(...)`（`ExplosiveCapability.java:397`）——一个圆形/方形范围爆炸。
美术 `assets/pvzce/animations/plant/special/squash.json` 有 `idle` / `grow` / `explode` 三段，
**缺 `armed` / `armed_loop`**，而 proximity 分支每 tick 都在发布 `armed_loop`
（`ExplosiveCapability.java:338-339, 366`）→ 静默回落 `idle` 并打 warn。

### 修法

1. **新能力 `pvzce:squash`（`common/capability/plant/SquashCapability.java`）**，不复用 explosive：
   - 触发：同格或相邻格出现敌方僵尸（沿用 `trigger_range` 的语义，默认 0.5 格）；
   - 动作：先播 `grow`（跳起来），60 tick 引信后砸下；
   - 命中：**只砸一只**（目标格/触发时锁定的那一只），伤害 = `9999` 但走
     `ZombieEntity.damage(...)` 的正常路径（这样护甲规则仍然生效，
     且不需要一个"必杀"后门）；相邻格子的僵尸**不吃伤害**——"压扁"是一只，不是一片；
   - 自己：命中后 `remove()`，播放 `explode` 段（这一段本来就是"压扁后的一摊"，名字叫错而已）；
   - **不留弹坑、不起火**（explosive 的 `leavesCrater` 不参与）。
2. 美术：`squash.json` 补 `armed` / `armed_loop` 两段（照 `potato_mine.json` 的形状，
   从 `grow` 的末帧 hold 成循环），或者在能力里不发布这两个状态。
   **选前者**：proximity 类植物都该有"已就位"的循环姿势，土豆地雷已经有了。
3. 罗马数值：`data/pvzce/plants/squash.json` 去掉 `pvzce:explosive` 块，换成
   `{"type": "pvzce:squash", "trigger_range": 0.5, "fuse_ticks": 60}`；
   保留 `sounds.explode`（`sfx/plant/squash_hmm` 本来就是"嗯？"那一声）。
4. 注册：`common/capability/PlantCapabilities.java`（常量 + `register`）、
   `common/PvzceIds.java`；`abilities` 预设表（`PlantBehaviorPresets`）按需加一条。
5. 验收：`MiniGameTest` 那一类写法——起真关卡，放一株倭瓜、一行放两只僵尸，
   跑够 tick，断言"被压的那只死了、旁边那只活着、地图上没有弹坑"。

---

## 缺陷 7：变异无尽与原版无尽在两个分类下

**用户原话**："目前变异无尽和原版无尽被放在了两个分类下，请你放回一个分类。"
**用户答复**：**全部并入 `survival` 一个分类，泳池版保留**。

### 根因

分类是**关卡 id 的第二段**（`api/util/LevelGrouping.java:120-137`）：
`pvzce:yard/endless/mutation_*`（`MutationLevels.java:50-52`）与
`pvzce:yard/survival/endless_pool`（`EndlessLevels.java:50-52`）天然落在两个页签。

### 修法

1. `MutationLevels.CATEGORY` 由 `"endless"` 改为 `"survival"`；
   删掉 `MutationLevels.bootstrap()` 里注册 `pvzce:endless` 分类的那三行。
2. `common/PvzceIds.java:359` 的 `CATEGORY_ENDLESS` 常量连同引用一起删；
   `PvzceConstants` / 语言键 `level_category.pvzce.endless`（`zh_cn.json:35`、`en_us.json`）一起删。
3. **关卡 id 变了**（`pvzce:yard/endless/mutation_normal` → `pvzce:yard/survival/mutation_normal`）：
   存档里按 id 记的解锁/通关会失效。这是**允许的 break change**；
   在 `WorldStore` 里不用写迁移（用户明确允许 break）。
4. 加一条测试：`LevelTabsTest`（新）断言"`yard` 主题下的分类集合里没有 `endless`，
   且有 `survival`"，并断言 `BuiltInRegistries.LEVELS` 里不再有关卡落在未注册分类
   （这条现在只有 `PvzceServer.validateLevelGroups` 在重载时打日志）。

---

## 缺陷 8：水壶对着植物使用的动画缺失、效果太弱、冷却要 15s

**用户原话**："水壶对着植物使用的动画缺失，且使用后也感觉不到效果，应该提升效果，并把冷却到15s。"

### 现状

- **动画其实有，但播在了屏幕光标上**：`client/gui/screens/InGameScreen.java:1508-1521`
  的 `swingDefaultToolCursor()` 对任何工具卡都 `cursor.play("attack")`，
  渲染在 `renderDefaultToolCursor()`（`:1448-1497`）。
- **那份 attack 片段本身是错的**：`assets/pvzce/animations/tool/watering_can.json` 的
  `attack` 把 4 根正常水壶骨头全部 `visible: false`（`:1786`），转而画**金色**变体
  （`:2614 / :2822 / :3031`）—— 点一下水壶，手里的壶会变成金壶 0.85 秒。
  正常路径没有任何代码会去选"金色变体"。
- **草坪上没有使用动画**：`LevelServer.waterPlant`（`:3024-3042`）只发
  `POOL_SPLASH` 粒子 + 浇水音效；植物本身没有"被浇"的表现。
- **效果**：`PlantEntity.water(...)` 回满血 + `wateredTicks = 15s`，
  加速系数 `WATERED_ACTION_SPEED = 4/3`（`PlantEntity.java:62,158-160`）。
- **冷却**：`data/pvzce/tools/watering_can.json:7` 是 `600`（10s）。

### 修法

1. **修 attack 片段**：把 4 根正常壶骨头的 `visible` 改回 `true`、金色变体删除或整段隐藏，
   水流的 8 根骨头保留。这是转换器的可见性映射问题，顺手看 `tools/reanim_to_pvzce.py`
   有没有把 `_gold` 变体错误地当成主图。
2. **补草坪上的表现**：新状态 `EntityAnimations.WATERED`（`"watered"`），
   时长与水流对齐（约 0.6s），客户端在植物身上播；
   同时**把粒子升级**：`POOL_SPLASH` 之外加一圈 `POTTED_ZEN_GLOW`（现在只在"真的回血/催熟"时才发）
   —— 改成**总是发**，因为用户抱怨的正是"看不出有没有效果"。
   → 验收标准：浇水后**画面上一定有三样东西**：植物身上的水花、一圈光晕、一次音效。
3. **提升效果**（数值）：
   - `WATERED_ACTION_SPEED` 由 `4/3` 提到 **`3/2`**（+50%），
     `PlantEntity.WATERED_ACTION_SPEED` 加常量注释说明这是本项目的取舍；
   - 冷却 `600 → 900`（15s，`data/pvzce/tools/watering_can.json` 与
     `data/pvzce/slots/watering_can.json` 两处都要改）；
   - `3_4.json` 里那处 `"cooldown": 0` 的关卡覆盖**保留**（那一关是教学关，水壶就该免费）。
4. 测试：`WateringCanTest`（新）断言"冷却 900"、"浇过的植物 `actionRate` 是 1.5 倍"、
   "浇一次回满血"、"`watered` 状态在客户端能被解析（美术文件里有该 clip）"。

---

## 缺陷 9：hints 显示后不停止，最多 15s

**用户原话**："某些关卡的hints显示后就不会停止了，请你在代码中规定最多15s。"

### 根因（两个叠加）

1. **`HintBox.hide()` 把时钟重新起算了**（`client/gui/hud/HintBox.java:141-153`）：
   ```java
   if (elapsed < holdNanos - fadeNanos) {
       holdNanos = Math.max(0L, elapsed);   // 新的 hold = 已经显示过的时长
       shownNanos = now;                    // 时钟归零
   }
   ```
   `alpha()`（`:165-182`）从**新的** `shownNanos` 量，于是淡出变成了"重新淡入 + 再显示同样久"。
2. `duration_ticks: 0`（`LevelHint.PERSISTENT`）会被翻译成 `Long.MAX_VALUE / 4`
   （`HintBox.java:105-113`），所以上面那个 `if` **永远成立**，
   于是 `InGameScreen.collectDrop` 的那次 `hide()` 不是收起、而是**把寿命翻倍**。
   点名的关卡：`3_1.json:384,389`、`3_3.json:459`、`3_4.json:602`、`3_5.json:632`（都在用 0）。

### 修法

1. 修 `hide()`：把截止时间**往前拉**而不是重起时钟 ——
   `long remaining = holdNanos - elapsed; holdNanos = elapsed + Math.min(remaining, fadeNanos);`
   （`shownNanos` 不动）。
2. **代码级硬上限 15s**（用户明确要求"在代码中规定"）：
   `LevelHint` 加 `public static final int MAX_DURATION_TICKS = 15 * 60;`，
   `HintBox.show(LevelHint)` 里 `Math.min(effectiveHold, MAX_DURATION_TICKS)`，
   **`PERSISTENT` 也走这个上限**。这样即使关卡写了 0，最长也只有 15 秒。
3. `HintBoxTest.hidingFadesRatherThanCuts`（`:56-64`）现在钉不住任何东西（它只断言
   `visible()` 立刻为真）—— 改成"显示超过 `FADE_SECONDS` 之后调 `hide()`，
   再等一个 `FADE_SECONDS`，断言 `!visible()`"。
4. 新增一条：`LevelHintTest` 断言"`duration_ticks: 0` 的提示在 15s 后消失"。
5. 顺手把 `3_1 / 3_3 / 3_4 / 3_5` 的 `duration_ticks: 0` 改成明确秒数
   （教学关的台词本来就该有节奏），代码上限是兜底而不是唯一防线。

---

## 缺陷 10（顺手）：3 条偶发用例改成确定性断言

`踩坑清单.md` 第 34 条记着三条读真实掷骰、在干净工作树上也会偶发红的用例：

- `GameplaySimulationTest.finishedLevelSaveDoesNotRestorePlants`（实测 6 次红 2 次）
- `MutationManagerTest.aReplacedBarComesBackAsTheReplacedBar`（12 次红 3 次）
- `MutationManagerTest.theApocalypseDoesNotGoOffTwice`（偶发）

`验证约定.md` §6 要求偶发失败必须当缺陷处理。做法：给这三条用例的 `LevelServer`
**播种**（`new Random(fixedSeed)`）或改用"不依赖落点"的断言（例如断言"存活植物数不增加"
而不是"某一株在不在"）。这一批顺手做掉，让"全绿"重新变成可靠信号。
