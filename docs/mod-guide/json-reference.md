# PVZCE JSON 参考

数据目录约定：`data/<ns>/<注册表目录>/<相对路径>.json`。
**id 由完整相对路径决定**（`plants/tier1/pea.json` → `<ns>:tier1/pea`），文件里写了 `id` 则以其为准。
类型标识均为 `namespace:path`；未写命名空间时默认 `pvzce`。

> 行为通过 **capabilities** 声明（可组合能力组件）。旧的 `behavior: "pvzce:shooter"` 写法仍能解析，但已降级为**预设语法糖**：显式写了 `capabilities` 就完全以它为准；两者都没有时该植物没有任何主动行为。

---

## plants

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| id | Identifier | 文件路径 | 植物 id |
| cost | ResourceCost | 空/300 | `resources` map + `cooldown` tick |
| health | int | 300 | |
| placement | PlacementDef | 见下 | `layer`(1) / `count`(1) / `group`(`plantable`)；**能种在哪由标签决定，不由这里决定** |
| capabilities | Capability[] | `[]` | 见下方「植物能力」 |
| behavior | Identifier? | 无 | 可选预设，见文首说明 |
| sounds | PlantSounds? | 无 | `{place, shoot, explode, produce, melee}`，每项是 sound_event id；缺省回退能力默认音效 |
| animation | Identifier? | 无 | 整实体动画文件覆盖；相对 `assets/<ns>/animations/`，省略 `.json` |
| animations | map<String,Identifier>? | 无 | 按状态覆盖动画文件，如 `{"walk":"mymod:walk_custom"}` |

### 植物能力

| type | 字段 | 说明 |
|---|---|---|
| `pvzce:shooter` | `interval`(90) `shots`[] `sound`? `first_delay`(0) | 直线射击；`shots` 元素为 `{projectile, damage, count}` |
| `pvzce:thrower` | `interval`(90) `shots`[] `butter_chance`(0) `butter_projectile`(`pvzce:butter`) `sound`? `first_delay`(0) | 抛物线投掷，按概率换成黄油弹 |
| `pvzce:producer` | `resource`(必填) `amount`(25) `every`(必填) `first_delay`(-1=300) `sound`? | 周期产出资源掉落物 |
| `pvzce:explosive` | `trigger`(`timed`\|`proximity`) `fuse_ticks`(60) `radius`(1.0) `damage`(1800) `trigger_range`(0.6) `sound`? | 樱桃炸弹 / 土豆雷共用 |
| `pvzce:melee` | `range`(0.7) `swallow_max_health`(0) `chew_ticks`(240) `sound`? | 吞噬弱僵尸后咀嚼消失 |
| `pvzce:boost_below` | `sound`? `boosted_sound`? | 立即强化下方植物并消耗自身（咖啡豆） |

```jsonc
{
  "id": "pvzce:pea_shooter",
  "cost": { "resources": { "pvzce:sun": 100 }, "cooldown": 300 },
  "health": 300,
  "capabilities": [
    { "type": "pvzce:shooter", "interval": 90,
      "shots": [ { "projectile": "pvzce:pea", "damage": 20, "count": 1 } ] }
  ],
  "sounds": { "shoot": "pvzce:sfx/plant/shoot_pea" }
}
```

---

## zombies

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| id | Identifier | 文件路径 | |
| health | int | 200 | |
| move_speed | float | 0.47 | 格/秒 |
| bite_damage | int | 100 | 每口伤害 |
| bite_interval | int | 60 | 咬间隔 tick |
| can_swim | bool | false | 为 false 时陆地僵尸进入水面格会淹死 |
| capabilities | Capability[] | `[]` | 见下方「僵尸能力」 |
| behavior | Identifier? | 无 | 可选预设 |
| sounds | ZombieSounds? | 无 | `{spawn, hit, armor_hit, bite, death, special}` |
| animation / animations | 同上 | 无 | |

> 行走与啃咬是所有僵尸共享的基础循环，写在 `ZombieEntity` 里；能力只覆盖"不同的部分"。

### 僵尸能力

| type | 字段 | 说明 |
|---|---|---|
| `pvzce:armor` | `pieces`[] `post_armor_speed`(-1) | `pieces` 元素为 `{id, durability, position(front\|top)}`；front 挡地面弹、top 挡抛射弹；`post_armor_speed > 0` 时护甲全碎后加速（报纸僵尸） |
| `pvzce:vault` | `jump_distance`(1.4) `sound`? | 跳过遇到的第一株植物（撑杆） |
| `pvzce:fly` | `height`(1.0) | 无视植物飞行；首次被命中即落地（气球） |
| `pvzce:dig` | `speed`(1.5) `emerge_ticks`(60) `sound`? | 潜地穿过草坪后从右侧破土（矿工） |
| `pvzce:hammer` | `interval`(90) `throws_imp`(false) `imp`(`pvzce:imp`) `imp_interval`(600) `imp_cells_ahead`(3) `sound`? | 一击摧毁所在格植物并可投掷小鬼（巨人） |
| `pvzce:boss_phases` | `phases`[] `summon_x_offset`(0.6) | `phases` 元素为 `{at_hp, summons[], ability(slam\|charge\|summon)}` |

```jsonc
{
  "id": "pvzce:buckethead_zombie", "health": 200, "move_speed": 0.18,
  "capabilities": [
    { "type": "pvzce:armor", "post_armor_speed": -1,
      "pieces": [ { "id": "pvzce:bucket", "durability": 1100, "position": "top" } ] }
  ]
}
```

---

## projectiles

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| id | Identifier | 文件路径 | |
| layer | String | `ground` | `ground` 会被飞行/潜地僵尸忽略；`air` 可命中它们 |
| capabilities | Capability[] | `[]` | 见下方「子弹能力」 |
| behavior | Identifier? | 无 | 可选预设（`pvzce:linear` / `pvzce:arc`） |
| sounds | ProjectileSounds? | 无 | `{impact}` sound_event id |
| animation / animations | 同上 | 无 | |

### 子弹能力

| type | 字段 | 说明 |
|---|---|---|
| `pvzce:linear` | `speed`(2.0) | 直线飞行，速度为格/秒 |
| `pvzce:arc` | `speed`(2.2) `gravity`(9.0) | 抛物线；发射时按目标位置解算初速 |
| `pvzce:splash` | `radius`(1.5) `sound`? | 命中点范围伤害**取代**直接命中 |
| `pvzce:status` | `effects`[] | 命中附加状态；元素为 `{status(slow\|immobilized), ticks(240), magnitude(0.7)}` |
| `pvzce:pierce` | 无 | 命中后继续飞行 |

```jsonc
{
  "id": "pvzce:butter", "layer": "air",
  "capabilities": [
    { "type": "pvzce:arc", "speed": 2.2, "gravity": 9.0 },
    { "type": "pvzce:status", "effects": [ { "status": "immobilized", "ticks": 240 } ] }
  ],
  "sounds": { "impact": "pvzce:sfx/plant/butter" }
}
```

---

## levels

关卡放在 `data/<ns>/levels/<主题>/<类别>/<关卡名>.json`，**文件位置就是分类**：前两段路径
成为 id 的前两段（`pvzce:yard/adventure/1_1`），也就是关卡选择页上的主题与类别。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Identifier | 建议与路径一致；文件里的显式 `id` 优先于路径，此时分类以 id 为准 |
| name/description | String | 关卡列表显示 |
| width/height | int | 默认 9×5；**大于默认值时实体网格会跟随关卡尺寸** |
| scene | map<Identifier, String[]> | 元素 id → `"x,y"` 列表；畸形/越界条目会被忽略并记录 |
| teams | TeamDef[] | id/name/win_condition |
| win_team | Identifier | 胜利目标队伍 |
| rules | map<Identifier, Json> | 规则覆盖；未知规则或非法取值会在加载时报告 |
| env_vars | map<Identifier, EnvValue> | type/value |
| waves | WaveDef[] | 见下方波次说明 |
| wave_interval_end_multiplier | float | 可选，默认 1.0；线性递减波次间隔 |
| slots | Identifier[] | **本关固定卡**（引用 slots 注册表）：进入关卡必定带上，且**无视背包**（关卡可以借卡给玩家）。留空即"不钉任何卡" |
| max_seed_slots | int | 可选，默认 6；本关最多能带入关卡多少张卡。会自动抬到不小于 `slots.size()` |
| unlock_resources | map<Identifier, bool> | 资源收集门槛；仍需卡槽中有对应资源卡才能收集 |
| initial_sun | int | 初始阳光 |
| rewards | LevelRewards | 可选；首通/重复通关奖励与僵尸掉币（见下） |
| music | LevelMusicDef? | 默认 grasswalk 循环；`cues`: `[{at_tick, track, event, loop, stop, volume, fade_seconds}]` |
| initial_entities | InitialEntityDef[] | 编辑器预摆：kind/id/x/y |
| dialogue | LevelDialogue? | 可选；关卡开始前的一段对话（见下） |

### 奖励（rewards）

```jsonc
"rewards": {
  // 首次通关发放（重复通关不再发）
  "first_clear": [ { "type": "unlock", "id": "pvzce:sunflower" } ],
  // 重复通关发放（首通不发）
  "repeat":      [ { "type": "coins", "amount": 100 } ],
  // 僵尸死亡掉币：掉哪种币、概率、数量
  "coin_drop": "pvzce:coin_silver",
  "coin_drop_chance": 0.25,
  "coin_drop_amount": 1
}
```

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| first_clear | Reward[] | `[]` | 首通奖励；`type:"unlock"` 需要 `id`（卡 id），`type:"coins"` 需要正数 `amount` |
| repeat | Reward[] | `[{coins:100}]` | 重复通关奖励；写 `[]` 可以完全不给 |
| coin_drop | Identifier | `pvzce:coin_silver` | 掉哪种币；四种面额可换（见下） |
| coin_drop_chance | float | `0.25` | 每只僵尸死亡时掉币的概率，0 表示不掉 |
| coin_drop_amount | int | `1` | 一次掉几枚 |

金币有**四种面额**，各自是一种资源，面值写在资源定义的 `default_value` 里（与 PVZ 一致）：

| 资源 id | 面值 | 美术 |
|---|---|---|
| `pvzce:coin_silver` | 10 | 银色硬币（原版 reanim） |
| `pvzce:coin_gold` | 50 | 金色硬币（原版 reanim） |
| `pvzce:diamond` | 1000 | 钻石（原版 reanim） |
| `pvzce:money_bag` | 250 | 钱袋（单张贴图） |

四者都是 `collectible_without_card: true`：捡钱不占卡槽。掉落时 `amount` 直接写面值，所以关卡内金币计数与写入存档的钱包数都只是把四种加起来。

- **首通判定**：`saves/<world>/level_status/<key>.dat` 是否存在。已经拿到的卡不会再发一次（会当成已拥有跳过）。
- **金币是跨关卡的**：本局捡到的金币在**关卡结束（胜负都算）**时计入 `saves/<world>/profile.dat`；奖励里的金币只有**植物方获胜**才发。
- `type` 拼错不会被 codec 拦住（它只是字符串），加载时由 `LevelValidator` 报出来。
- 僵尸掉币用的资源是 `pvzce:coin`（`collectible_without_card: true`，捡钱不需要卡槽）。

### 开场对话（dialogue）

关卡可以在**开局时**先播一段对话：场景照常渲染，角色立绘从说话的边站上来，台词**逐字打出来**
（打字机），点击推进到下一句（**ESC 跳过整段**）。台词打到一半时点一下会**先补全整句**，再点才进
下一句；气泡大小在开打之前就按整句算好，所以不会一边打字一边变形。写了 `dialogue` 的关卡在**每一次新开一局**时都会播，
继续一局存档不会重播；通关后再玩一遍同样会播。

```jsonc
"dialogue": {
  "lines": [
    { "character": "pvzce:pea_chan", "portrait": "welcome",
      "text": "欢迎来到植物和僵尸的世界", "voice": "", "side": "left" }
  ]
}
```

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| character | Identifier | 必填 | 说话的角色，引用 `dialogue_character` 注册表（见下） |
| portrait | String | `""` | **文件名**（不带 `.png`），位于该角色的立绘目录下；留空则不画立绘 |
| text | String | `""` | 台词；按气泡宽度逐字换行，写 `\n` 可强制换行 |
| voice | Identifier | `""` | 播哪条音效（`assets/<ns>/sounds/...` + `sounds.json` 里的事件 id）；留空＝不播 |
| side | `left` \| `right` | `left` | 角色站哪一边；气泡自动画在对侧，尾巴指向角色 |

- 对话由**客户端**播放，数据也由客户端从同一份数据包读取（与 `usesConveyorBelt` / `lockedSlotsFor` 同一层）。
- 播放位置取决于这一局怎么进的：走**选卡界面**的关卡在关卡入口（镜头横摇之前）播；**传送带关卡**这类
  直接进关卡的，在关卡内播，播放期间整关暂停（客户端发 `PauseGameC2S(true)`），读完再开始。
- `LevelValidator.validateDialogue` 在加载时报告未知角色、拼错的 `side`、空台词与非法立绘名；
  立绘/气泡贴图是否存在由服务端在 `/reload` 时报告（`[PVZCE/对话]` 日志 + 控制台提示）。

### 背包与卡池

玩家的**背包**（`saves/<world>/profile.dat`）决定他能自选哪些卡：新世界默认只有豌豆射手 + 铲子，资源卡（阳光）永远可用。于是：

```
实际卡组 = 关卡的 slots（固定，无视背包） + 玩家从「背包已解锁卡 − 固定卡」里选，填到 max_seed_slots
```

- 固定卡数 ≥ `max_seed_slots` 时玩家没有可选空间（原版 1-1 就是这样），选卡页放完僵尸预览后会自动开始。
- 想让玩家有选择空间：`max_seed_slots` 大于 `slots.size()` 即可；留空 `slots` 则完全由背包决定。
- 解锁卡走 `rewards.first_clear`；创造沙盒世界可用「创建世界」对话框的**全解锁**开关。

### 冒险模式前三关（内置示例）

`data/pvzce/levels/yard/adventure/1_{1,2,3}.json` 是照原版节奏写的第一批关卡，也是最省事的模板：

| 关卡 | 草坪 | 固定卡/总卡槽 | 自选 | 首通奖励 |
|---|---|---|---|---|
| 1-1 | 9×1 | 豌豆 + 阳光卡 / 2 | —（选卡页自动开始） | 向日葵 |
| 1-2 | 9×3 | 豌豆 + 阳光卡 / 4 | 2 张 | 樱桃炸弹 |
| 1-3 | 9×3 | 豌豆 + 阳光卡 / 5 | 3 张 | 坚果墙 |

想让玩家**有得选**，就照 1-2/1-3 那样把 `max_seed_slots` 写成大于 `slots.size()`；想让关卡**发一套固定卡**，就照 1-1 那样让两者相等（相等时选卡页放完僵尸预览会自动开始）。

> **阳光卡要占卡槽**：`pvzce:sun` 的 `collectible_without_card` 是 `false`，所以关卡必须在 `slots` 里列出阳光卡，玩家才收得到阳光、左上角才会出现银行。想让某关卡住另一种资源的收集，用一个同样 `collectible_without_card: false` 的资源（比如 `pvzce:redstone`）并在 `unlock_resources` 里解锁它。

### 波次（WaveDef）

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| type | `small` \| `huge` \| `final` | `small` | `huge` 播放大波音效/预警；最后一波自动视为 `final` |
| delay | int | 必填 | tick；相对上一波生成时刻，第一波相对关卡开始 |
| warning_ticks | int | 600 | 大波/终波提前预警窗口；仅 huge/final 生效 |
| entries | `[{id, count}]` | 必填 | 精确僵尸组成；`count` 缺省 1 |

波次节奏：

- 服务端维护波次进度条，上一波生成后按 `delay × lerp(1.0, wave_interval_end_multiplier, 波次进度)` 填充；
- 进度填满触发下一波；同一波内 entries 展开后随机打乱，每 15 tick 生成一只，行随机且尽量均匀；
- 进度条在大波/终波段画旗帜，进入预警窗口时显示"一大波僵尸正在接近！"；
- 音效：small 静音，huge 播 `pvzce:sfx/ambient/hugewave`，final 播 `pvzce:sfx/effect/awooga` + `pvzce:sfx/ambient/hugewave`。

---

## dialogue_characters

一个能说话的角色：显示名、立绘目录、立绘大小、说话用哪个气泡。加一个新角色＝加一个文件
＋一个立绘文件夹，不需要写代码。

```jsonc
// data/<ns>/dialogue_characters/pea_chan.json
{
  "id": "pvzce:pea_chan",
  "name": "豌豆酱",
  "portrait_dir": "pvzce:textures/gui/dialogue/pea_chan",  // 缺省 = textures/gui/dialogue/<path>
  "scale": 1.0,                                            // 立绘大小倍率（0.2~3）
  "box_left": "pvzce:textures/gui/dialogue/box_left",      // 缺省 = 内置气泡
  "box_right": "pvzce:textures/gui/dialogue/box_right"
}
```

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| id | Identifier | 必填 | 关卡里 `dialogue.lines[].character` 引用的就是这个 id |
| name | String | id 的 path | 气泡上沿的小字；缺省时显示 id 的最后一段 |
| portrait_dir | Identifier | `textures/gui/dialogue/<path>` | 立绘目录；`portrait: "smile"` 解析成 `<目录>/smile.png` |
| scale | float | `1.0` | 立绘高度倍率（相对窗口高度的 62%），夹在 0.2~3 |
| box_left / box_right | Identifier | 内置 `textures/gui/dialogue/box_{left,right}` | 说话人站在左/右时用的气泡贴图 |

- 立绘是带透明通道的 PNG，按原图比例缩放、贴屏幕下沿；`side` 是左就把立绘画在左下、气泡画在右上（反之亦然）。
- 气泡按九宫格拉伸：左右各 56px、上下各 24px 保持不变——**尾巴在左下/右下角那一块里**，
  所以那两块必须够宽够高，改贴图时要保证尾巴仍然落在 56×24 的角内。
- 编辑器有独立的**「对话」页**：左侧台词列表（顺序即播放顺序），右侧选角色（下拉列表）、
  选立绘（列出该角色目录下的 PNG）、台词、语音、左右位置。

---

## level_themes / level_categories

关卡选择页的两级导航：左侧列是**主题**，顶部行是**类别**。两者各是一份数据文件，都只有两个
字段——**没有 `name`**：显示名与植物/僵尸一样走语言文件，key 按 `GuiLang` 的 `<ns>.<path>`
规则生成（`pvzce:yard` → `"pvzce.yard"`），缺失时回退到 id 的最后一段。

```jsonc
// data/mymod/pvzce/level_themes/redstone.json
{ "id": "mymod:redstone", "order": 10 }

// data/mymod/pvzce/level_categories/minigame.json
{ "id": "mymod:minigame", "order": 1 }
```

- `order` 决定页签顺序（小的在前，相同按 id 排序）；**未分类**页永远排在最后。
- 一个类别只声明一次，可以被任意多个主题使用；某个主题下出现哪些类别，由该主题下**实际存在
  的关卡**决定（见 [levels](#levels)），所以不需要、也没有「主题 → 类别」的声明字段。
- 一个关卡若引用了未注册的主题/类别，会被归入未分类页，并在 `/reload` 时输出原因。

---

## resources / slots / tools / scene_elements

- `resources`：`id` / `default_value` / `collectible` / `icon` / `drop_anim` / `max_stack` / `collectible_without_card`
- `slots`：`id` / `kind`(`plant`|`resource`|`tool`) / `content` / `cost` / `icon`（可选；缺省走 `EntityArt.sprite`，即定义的 `texture`，再回退 `textures/entities/<content>`）

### 波次出怪间隔（`spawn_interval`）

```jsonc
"waves": [
  { "type": "small", "delay": 1500, "entries": [...], "spawn_interval": 480 },  // 8 秒一只
  { "type": "final", "delay": 1500, "entries": [...], "spawn_interval": 150 }   // 2.5 秒一只
]
```

写在**每一波**上：简单关卡的前期可以慢、最终波必须快，这是两个独立的旋钮。缺省 300 tick（5 秒），下限 15 tick。

一波放完要早于下一波到来（`(僵尸数-1) × spawn_interval ≤ 下一波的 delay`），否则下一波的进度条会在上一波还在出怪时就开始读秒。

### 资源掉落方式（`drop_motion`）

```jsonc
"drop_motion": "fall"   // 从上方 2.2 格落下（默认；天上掉的阳光）
"drop_motion": "rise"   // 从生成点上浮 0.5 格再落回（向日葵产出的阳光）
"drop_motion": "landed" // 生成即落地，不移动（金币、能量豆）
```

写错的值会退回 `fall`，并由 `LevelValidator` 在 `/reload` 时点名。植物「产出」的资源自动用 `rise`，不需要在资源定义里写死。

### 渲染缩放（`render_scale`）

```jsonc
"render_scale": 1.2   // 可选，默认 1；客户端把这个内容画大/画小这么多倍
```

`plants` / `zombies` / `projectiles` / `resources` 四种定义都接受它。**纯表现**：不改变命中盒、收集半径、碰撞或任何服务端模拟，只影响画出来的大小；两个轴同倍率，所以不会把圆画成椭圆。夹紧在 0.05~8（写超范围是夹紧，不是报错），未声明即 1（和历史行为一致）。

它**不进网络包**：客户端本来就会加载同一份数据包，两侧各自从定义里读同一个数。解析只有一处 —— `EntityArt.renderScale`，动画、无动画时的贴图兜底、以及影子都读它。

### 大波提示（`warning_ticks`）

`warning_ticks` 是**公告时长**：大波/最后一波到来前的这么多 tick 里，「一大波僵尸正在接近」会闪烁并响一次。它既是持续时间，也是"要不要公告"的开关（0 = 不公告）。内置关卡用 180 tick（3 秒）——足够看见，又不至于变成常驻横幅。

### 关卡解锁条件（`unlock`）

关卡默认**任何玩家都能进**；写了 `unlock` 块才有门槛。三个条件全部满足（AND）才解锁：

```jsonc
"unlock": {
  "requires": [
    { "type": "level", "id": "pvzce:yard/adventure/1_1" },  // 该关必须已通关
    { "type": "card",  "id": "pvzce:sunflower" },           // 该卡必须已解锁
    { "type": "coins", "amount": 500 }                      // 钱包至少这么多金币
  ],
  "cost": 1000,      // 可选：花这笔金币一次性买下本关（买过就永久可玩）
  "hidden": false    // true = 未解锁时整块不显示在关卡列表里
}
```

- `type` 是字符串判别式，**拼错不会报 codec 错**，但会被挡下（`LevelValidator` 在加载时点名，规则本身也把未知类型当作"未满足"），所以不会出现"写了条件却等于没写"。
- `cost` **不等于** `coins`：`coins` 只查余额（花到别处就可能重新锁上），`cost` 是花掉并永久记录。
- 前置关卡指向一个不存在的 id，或两关互相等待，`LevelValidator` 会在 `/reload` 时报出来 —— 否则那关永远进不去，而游戏不会说为什么。
- 未解锁的关卡在列表里**置灰并显示缺什么**；`hidden: true` 才完全不出现。

  - `content` 指向真正的植物/工具/资源 id。**关卡卡池引用的是 slot id**；若没有对应的 `slots/*.json`，也可以直接写植物 id（兼容路径）。
- `tools`：`id` / `use_cost` / `cooldown` / `targets`(`cell`|`plant`|`zombie`) / `effect` / `uses`（`-1` 为无限次）
- **`levels` 的卡池字段**：`slots` 是**关卡自己的卡** —— 进入关卡必定发放，玩家不能取消。`max_seed_slots` 是**总卡槽数**；它减去 `slots` 的数量就是玩家能自选的格数（`max_seed_slots` 小于 `slots` 数量时会自动抬到 `slots` 的长度）。想让玩家有得选，就让 `slots` 比 `max_seed_slots` 短。
- `scene_elements`：`id` / `surface`(`GRASS`|`GROUND`|`WATER`|`ROOF`|`ROOF_SLOPE`|`GRAVE`|`CRATER`) / `max_height` / `liquid`(可选，指向一个液体 id)。**没有 `accepts` 字段**：一个瓦片能种什么，完全由它在 `scene_element` 注册表里的标签决定（见下节）
- `liquids`：`id` / `base_texture` / `shallow_color` / `deep_color` / `opacity` / `depth_scale` / `foam{color,width}` / `wave{speed,amplitude,density}` / `caustics` / `reflect_color` / `fresnel` / `specular` / `specular_power` / `static_frames`（全部可选，颜色用 `#RGB`|`#RRGGBB`|`#RRGGBBAA`）。详见 [rendering.md](rendering.md#水面液体)

---

## 标签（tags）

目录：`data/<ns>/tags/<注册表目录或注册表 id>/<名字>.json`，**单数（`plant`）与复数（`plants`）两种拼写都接受**。

```jsonc
{ "replace": false, "values": [ "pvzce:pea_shooter", "#other:group" ] }
```

标签 id = `<命名空间>:<注册表目录下的相对路径>`，与 MC 一致：`data/c/tags/plant/plantable.json` 就是 `#c:plantable`。
（旧的 `data/<ns>/tags/pvzce/<注册表>/<名字>.json` 三段式仍能加载，解析成同一个标签 id。）

**标签按注册表分开**，和 MC 相同：`#c:plantable`（`scene_element`）与 `#c:plantable`（`plant`）是两个互不影响的标签。
引用不存在的注册表条目、循环引用、非法条目都会在加载时报告。

### 约定标签（`c` 命名空间）

放置规则**只读标签**，不读任何硬编码 id。内置包提供下面这些；模组应当往同名标签里追加自己的条目，而不是另起一套。

`data/c/tags/scene_element/`（瓦片能接受什么）：

| 标签 | 含义 | 内置成员 |
|---|---|---|
| `#c:ground` | 算作实地：载体能立在上面，需要土地层的植物属于这里 | grass、ground、roof_flat、roof_slope |
| `#c:plantable` | 可以直接种植物 | grass、roof_flat、roof_slope |
| `#c:water` | 水面 | water |
| `#c:unplantable` | 什么都不接受 | grave、crater |

`data/c/tags/plant/`（植物是什么、需要什么）：

| 标签 | 含义 | 内置成员 |
|---|---|---|
| `#c:plantable` | 可以承载别的植物 | flower_pot、lily_pad |
| `#c:carrier` | 载体：先放它，再在它上面放植物 | flower_pot、lily_pad |
| `#c:requires_ground` | 必须插在地里（不能在水上、不能叠在植物或载体上） | flower_pot、potato_mine |
| `#c:water_plant` | 只能种在水面上 | lily_pad |
| `#c:plant_only` | 只能种在另一个植物上（下方任意植物都算） | coffee_bean |

### 放置矩阵

瓦片 → 能放什么：

| | 普通植物 | 花盆 | 莲叶 | 土豆雷 | 咖啡豆 |
|---|---|---|---|---|---|
| 草地 grass | ✅ | ✅ | ❌ | ✅ | ❌（需先有植物） |
| 裸地 ground | ❌ | ✅ | ❌ | ✅ | ❌ |
| 屋顶 roof_flat / roof_slope | ✅ | ✅ | ❌ | ✅ | ❌ |
| 水面 water | ❌ | ❌ | ✅ | ❌ | ❌ |
| 墓碑 grave / 弹坑 crater | ❌ | ❌ | ❌ | ❌ | ❌ |

再叠加一层：

| 已有 | 上面能放 |
|---|---|
| 花盆 / 莲叶 | 普通植物；咖啡豆 |
| 普通植物（豌豆等） | 咖啡豆 |
| 土豆雷 | 普通植物（同层、不同互斥组，可共存） |

### 互斥组与分层（`placement`）

`placement` 只管"叠放"，不管"能不能种"：

- `layer`：堆叠层。载体 `0`，地面植物 `1`，植物上的植物 `2`。判定只看**比自己层低的**东西，所以"先种雷再种豌豆"和反过来结果一致。
- `count`：占几层（默认 1）。
- `group`：互斥组名，同组不能同格；**留空字符串表示同格可多个**（咖啡豆）。默认 `plantable`＝每格一个普通植物；载体默认 `carrier`（盆上不能套盆）。

---

## 内置注册表分类

`/pvzce registry list <category>` 与命令补全共用同一张分类表：

`plant`、`zombie`、`projectile`、`resource`、`slot`、`tool`、`scene_element`、`liquid`、`game_rule`、`env_var_type`、`sound_event`、`level`、`plant_capability`、`zombie_capability`、`projectile_capability`

复数别名（`plants`、`zombies`、`scene`、`levels`…）同样被接受。

---

## 自定义能力类型

1. 实现 `PlantCapability` / `ZombieCapability` / `ProjectileCapability`；
2. 提供 `MapCodec<T>`；
3. 在入口点注册：

```java
BuiltInRegistries.registerStatic(BuiltInRegistries.PLANT_CAPABILITIES,
        "mymod:laser", new CapabilityType<>(Identifier.of("mymod", "laser"), LaserCapability.CODEC));
```

之后 JSON 里即可写 `{ "type": "mymod:laser", ... }`。

能力实例由 `instantiate()` 每株植物各创建一份，`save(CompoundTag)` / `load(CompoundTag)` 用于随存档持久化（父级会以类型 id 为键存子标签）。
