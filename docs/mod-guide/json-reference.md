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
| `pvzce:shooter` | `interval`(90) `shots`[] `sound`? `first_delay`(0) | 直线射击；`shots` 元素为 `{projectile, damage, count, row_offset, backward, rows, range}`（`range` 是这一发能飞几格，0 = 不限，小喷菇用 3） |
| `pvzce:thrower` | `interval`(90) `shots`[] `butter_chance`(0) `butter_projectile`(`pvzce:butter`) `sound`? `first_delay`(0) | 抛物线投掷，按概率换成黄油弹 |
| `pvzce:producer` | `resource`(必填) `amount`(25) `every`(必填) `first_delay`(-1=300) `sound`? | 周期产出资源掉落物 |
| `pvzce:explosive` | `trigger`(`timed`\|`proximity`) `fuse_ticks`(60) `radius`(1.0) `damage`(1800) `trigger_range`(0.6) `sound`? `damage_type`(`pvzce:ash`) | 樱桃炸弹 / 土豆雷共用。**引信期间不可被伤害**（僵尸照咬，但咬不掉）；爆炸后植物会多留 30 tick 播完 `explode` |
| `pvzce:melee` | `range`(0.7) `swallow_max_health`(0) `chew_ticks`(240) `sound`? | 吞噬弱僵尸后咀嚼消失 |
| `pvzce:wake_below` | `sound`? `wake_sound`? | 唤醒下方睡觉的植物并消耗自身（咖啡豆）。对**醒着的**植物无事发生（豆子照样被消耗），`wake_sound` 只在真的叫醒时播 |
| `pvzce:nocturnal` | 无 | 蘑菇：白天睡觉（不射击、不产出，播 `sleep` clip），入夜自动醒来；被咖啡豆唤醒后**永久**不睡。状态由关卡时钟推出，只有"被唤醒过"进存档 |

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
| equipment | Equipment[]? | `[]` | 会磨损的装备（路障/铁桶/旗帜…），见下 |
| drops_arm | bool | true | 半血是否掉外侧手臂；巨人/小鬼/Boss 与手臂骨骼命名不同的僵尸设 false |
| drops_head | bool | true | 死亡时是否抛出头颅（`pvzce:zombie_head` 粒子）。**僵尸模型在死亡 clip 里把 head 藏起来了**，所以头是由这个粒子抛出来的；巨人/Boss 整个倒下、气球僵尸的头随气球走，都设 false |
| hidden_bones | string[] | `[]` | 永远不画的模型骨骼名。原版美术是**分层**而不是父子：同一只手可能有两份精灵、都挂在 root 下，于是两份都会画出来。见下面的旗帜说明 |

> 行走与啃咬是所有僵尸共享的基础循环，写在 `ZombieEntity` 里；能力只覆盖"不同的部分"。

### 僵尸装备（`equipment`）

一件**会磨损的装备**。外观由动画模型里的**骨骼族**决定：`art: "cone"` 指 `cone_1`（完好）、
`cone_2`、`cone_3`（越破越靠后）。模型里每个档位是一根骨骼，由
`tools/reanim_to_pvzce_all.py` 的 `damage_states` 按宿主骨骼的动画键复制出来并设为不可见；
**没有这些骨骼的内容不会报错**，只是没有破损外观。

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| art | string | 必填 | 骨骼族前缀（模型里的 `<art>_1`、`<art>_2`…） |
| piece | Identifier? | 无 | 由**这件护甲**的剩余耐久驱动；省略则由**本体血量**驱动 |
| health_below | float | 0.5 | 只有血量驱动时用：血量比例低于它换成最破的那张 |
| drop_particle | Identifier? | 无 | 打碎（或死时还戴着）时飞出去的粒子 |
| host | string? | 无 | 这件装备**自己带来的那只手**的骨骼名（旗帜是 `flaghand`）。它不是装备的某一档图，所以不在 `art` 的档位族里；作用是回答"这只手还在不在" |
| arm_bones | string[] | `[]` | 僵尸**自己的**手臂骨骼（`innerarm_*` / `outerarm_*`）。与 `host` 互斥：`host` 在画时它们被隐藏，`host` 不画时（例如死亡 clip 把旗子收走）它们被还回来 |

```jsonc
"equipment": [
  { "art": "cone", "piece": "pvzce:cone", "drop_particle": "pvzce:zombie_traffic_cone" },
  { "art": "zombie_flag", "health_below": 0.5, "drop_particle": "pvzce:zombie_flag",
    "host": "flaghand",
    "arm_bones": ["innerarm_hand", "innerarm_lower", "innerarm_upper",
                  "outerarm_hand", "outerarm_hand_2", "outerarm_upper",
                  "outerarm_upper_2", "outerarm_lower"] }
]
```

> **`host` + `arm_bones` 是为什么存在**：原版把一条手臂拆成两份精灵——普通僵尸的 `Zombie.reanim`
  里有一套（内外两条都一直画），旗帜僵尸的拿旗那只手在 `Zombie_flagpole.reanim` 里。于是
  一只旗帜僵尸会同时画出拿旗的手和一条普通的手臂，看起来像"一只悬空的手抓着旗子"。
  `host` 说明哪根骨骼代表"这只手在场上"，`arm_bones` 列出该被换掉的普通手臂骨骼：两者
  只画一个。范围是"`host` 在画的那几帧"，所以死亡 clip 把旗子收走之后普通手臂会回来——
  否则僵尸会无臂倒下。

护甲驱动的三档按剩余/耐久取：> 2/3 完好、> 1/3 第二张、其余第三张、0 = 消失；
族的图比档位少时（旗帜只有 `_1` 和 `_3`）退回最接近的那一张。装备只在**当前这一帧本来就画这个族**
时替换，所以死亡、喘气之类故意不画装备的 clip 不受影响。

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
| `pvzce:splash` | `radius`(1.5) `sound`? `damage_type`(`pvzce:splash`) | 命中点范围伤害**取代**直接命中 |
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
| max_seed_slots | int | 可选；**不写 = 跟随玩家背包**（新世界默认 8，见下），写了就是本关的总卡槽数。两种情况都会自动抬到不小于 `slots.size()` |
| mechanics | TypedMechanic[] | 可选；关卡机制，见「关卡机制（mechanics）」 |
| unlock_resources | map<Identifier, bool> | 资源收集门槛；仍需卡槽中有对应资源卡才能收集 |
| initial_sun | int | 初始阳光 |
| rewards | LevelRewards | 可选；首通/重复通关奖励与僵尸掉币（见下） |
| music | LevelMusicDef? | 默认 grasswalk 循环；`cues`: `[{at_tick, track, event, loop, stop, volume, fade_seconds}]` |
| initial_entities | InitialEntityDef[] | 编辑器预摆：kind/id/x/y |
| dialogue | LevelDialogue? | 可选；关卡开始前的一段对话（见下） |
| hints | LevelHint[] | 可选；底部灰色提示框的台词（见下） |

### 时间与夜晚（`rules` 里的三条）

| 规则 | 默认 | 说明 |
|---|---|---|
| `pvzce:day_length` | `0` | 白天长度（tick）；`0` = 没有白天 |
| `pvzce:night_length` | `-1` | 夜晚长度；`-1` = 没有夜晚 |
| `pvzce:sun_spawn_chance` | `0.001` | 每 tick 从天上掉一颗阳光的概率，`0` = 不掉 |
| `pvzce:graves_spawn_night` | `true` | 夜晚时每座墓碑每 tick 摇一次（约 15 秒一只）；2-1 这类"墓碑只是障碍"的关卡记得写 `false` |

四种写法与它们的含义（判据在 `DayNightCycle`）：

| 写法 | 含义 |
|---|---|
| `day_length: 0`, `night_length: -1` | **永远白天**（1-1~1-9 都是这一种） |
| `day_length: 0`, `night_length > 0` | **永远夜晚**（2-1：没有白天可回，客户端第一帧就是暗的） |
| `day_length: D > 0`, `night_length: -1` | 同样永远白天 |
| `day_length: D > 0`, `night_length: N > 0` | 真正的昼夜循环，每 `D + N` tick 一轮（1-10：一分钟白天 + 一百分钟夜晚） |

`rules` 里还有这些（写错规则名或写非法取值都会在加载时被 `LevelValidator` 报出来）：

| 规则 | 默认 | 说明 |
|---|---|---|
| `pvzce:sun_value` | `25` | 一颗阳光值多少 |
| `pvzce:zombie_sun_drop_chance` | `0` | 僵尸死亡时掉阳光的概率，`0` = 不掉（2-5 用 `0.03`，因为那一关没有产出植物、天上也不掉） |
| `pvzce:zombie_sun_drop_count` | `3` | 一次掉几颗；概率是**按这个颗数读的**，所以"少而值钱"要同时调这两个。掉落物是 `landed`，落在僵尸倒下的那一格或它相邻的格里 |
| `pvzce:crater_recovery` | `6000` | 弹坑多久长回草地（tick） |
| `pvzce:zombie_damage_multiplier` | `1` | 僵尸伤害倍率 |
| `pvzce:zombie_speed_multiplier` | `1` | 僵尸移速倍率（1-5 用 `1.5`） |
| `pvzce:plant_damage_multiplier` | `1` | 植物伤害倍率 |
| `pvzce:seed_cooldown_multiplier` | `1` | **卡片冷却倍率**；`0.3333` = 本关所有卡片冷却只有平时的三分之一（睡眠剥夺用的就是它）。卡自己的冷却仍写在植物/工具定义里，关卡只做缩放 |

### 提示文本（hints）

屏幕底部那个灰色半透明框（原版同款位置）的台词。一条提示写成：

```jsonc
"hints": [
  // 关卡开始时显示，一直挂到玩家捡到第一颗阳光
  { "trigger": "on_start", "text": "点击阳光可以收集", "duration_ticks": 0 },
  // 第一次捡到阳光时显示，默认 160 tick（约 2.7 秒）
  { "trigger": "on_resource", "resource": "pvzce:sun", "text": "你需要收集阳光种植植物" },
  // 让本关在卡被拒绝时也能说话（"冷却中"/"阳光不足"两句是内置的）
  { "trigger": "on_card_refused" }
]
```

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| trigger | string | `on_start` | `on_start`（关卡开始）/ `on_resource`（第一次捡到指定资源）/ `on_card_refused`（点了一张用不了的卡）。拼错的 trigger 退回 `on_start`，并由 `LevelValidator` 报出来 |
| resource | Identifier? | 无 | 只有 `on_resource` 用；不写或写了没注册的资源，这条永远不会触发（同样会被校验报出来） |
| text | string | `""` | 显示的文本；`on_card_refused` 不需要（它有自己的两句话） |
| duration_ticks | int | 160 | 停留时长；**写 0（或负数）表示常驻**，直到关卡结束或被下一条顶掉 |

**同时只显示一条**，且**卡牌拒绝的提示优先于教学提示**：拒绝是对玩家刚做的一个动作的回答，
被教学文本盖住就正好在玩家问的那一刻把答案吞了。每条 `on_start` / `on_resource` 提示
**一个关卡实例只触发一次**（那是课，不是通知）。

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

一条奖励（`Reward`）是三种形状之一：

| `type` | 需要的字段 | 发什么 |
|---|---|---|
| `unlock` | `id`（卡 id） | 把一张植物/工具卡放进背包。**不看首通标记**：任何一次通关只要背包里还没有它就会补发 |
| `coins` | 正数 `amount` | 直接进钱包 |
| `resource` | `id`（资源 id）+ 正数 `amount` | 发放 `amount` 个该资源，**钱包按资源定义的 `default_value` 折算**（`pvzce:diamond` 一个就是 1000 金币），并且结算页把你发的那个东西画进相框（如睡眠剥夺首通发一颗钻石）。第一项之外的资源奖励照常折算，只是没有画 |

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| first_clear | Reward[] | `[]` | 首通奖励；每一项见上表（`unlock` / `coins` / `resource`） |
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
- **编辑器只编辑"一张首通卡 + 一条重复金币"**：`resource` 这类它没有输入框的条目会**原样写回**（不会因为"编辑了一下"被删掉），但页面上看不到，也改不了。
- `type` 拼错不会被 codec 拦住（它只是字符串），加载时由 `LevelValidator` 报出来；`resource` 的 `id` 指向不存在的资源同样会被报出来（否则首通会发一个不值钱的东西）。
- 僵尸掉币用的资源是 `pvzce:coin`（`collectible_without_card: true`，捡钱不需要卡槽）。

### 开场对话（dialogue）

关卡可以在**开局时**先播一段对话：场景照常渲染，角色立绘从说话的边站上来，台词**逐字打出来**
（打字机），点击推进到下一句（**ESC 跳过整段**）。台词打到一半时点一下会**先补全整句**，再点才进
下一句；气泡大小在开打之前就按整句算好，所以不会一边打字一边变形。写了 `dialogue` 的关卡在**每一次新开一局**时都会播，
继续一局存档不会重播；通关后再玩一遍同样会播。

**播不播还取决于玩家的「剧情」开关**（关卡选择页左上角，存在客户端 `config/pvzce-client.toml` 的 `story`，缺省开）：
关掉之后客户端根本不会构造这段对话，两条播放路径（选卡页 / 关卡内）一起静默，关卡数据不用为它改任何东西。

```jsonc
"dialogue": {
  // 整段对话的首尾演出：第一句的立绘从自己那一侧滑入（center 从下方升起），
  // 最后一句点完时滑出，滑完才继续（选卡页开始平移 / 关卡开始）。缺省 slide。
  "enter": "slide",      // slide | none
  "exit": "slide",       // slide | none
  "lines": [
    { "character": "pvzce:pea_chan", "portrait": "welcome",
      "text": "欢迎来到植物和僵尸的世界", "voice": "", "side": "left" },
    // 每条台词可以加一个动画，缺省无：抖动一次，或改这一句立绘的大小
    { "character": "pvzce:pea_chan", "portrait": "panic", "text": "诶诶诶！", "side": "left",
      "animation": { "type": "shake", "amount": 2 } },
    { "character": "pvzce:pea_chan", "portrait": "evil_smile", "text": "看招！", "side": "center",
      "animation": { "type": "scale", "scale": 1.25 } }
  ]
}
```

**整段的首尾各演一次**：中间换说话人不动画（这正是"这两个效果＝这场对话开始了 / 结束了"的意思）；
ESC 跳过整段不会补动画。**每条的动画**只在这条台词开始时播一次：`shake` 的 `amount` 是幅度倍数
（缺省 1，`0` = 不抖），`scale` 的 `scale` 是这一句立绘的尺寸倍率（缺省 1.25，上限 1.6：立绘以底边为基准
缩放，再大头顶就会被窗口裁掉）；下一条没写 `animation` 就回到原尺寸。拼错的 `enter`/`exit`/`type`
由 `LevelValidator` 报出来，表现上退回缺省（滑入 / 无动画）。

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| enter / exit | `slide`\|`none` | `slide` | 整段对话的入场/出场演出；见上方示例 |
| character | Identifier | 必填 | 说话的角色，引用 `dialogue_character` 注册表（见下） |
| portrait | String | `""` | **文件名**（不带 `.png`），位于该角色的立绘目录下；留空则不画立绘 |
| text | String | `""` | 台词；按气泡宽度逐字换行，写 `\n` 可强制换行 |
| voice | Identifier | `""` | 播哪条音效（`assets/<ns>/sounds/...` + `sounds.json` 里的事件 id）；留空＝不播 |
| side | `left` \| `right` | `left` | 角色站哪一边；气泡自动画在对侧，尾巴指向角色 |
| animation | `{type, amount?, scale?}`? | 无 | 这条台词的动画：`shake`（抖一次，`amount` 幅度倍数）或 `scale`（立绘尺寸倍率）；缺省无动画 |

- 对话由**客户端**播放，数据也由客户端从同一份数据包读取（与 `usesConveyorBelt` / `lockedSlotsFor` 同一层）。
- 播放位置取决于这一局怎么进的：走**选卡界面**的关卡在关卡入口（镜头横摇之前）播；**传送带关卡**这类
  直接进关卡的，在关卡内播，播放期间整关暂停（客户端发 `PauseGameC2S(true)`），读完再开始。
- `LevelValidator.validateDialogue` 在加载时报告未知角色、拼错的 `side`、空台词与非法立绘名；
  立绘/气泡贴图是否存在由服务端在 `/reload` 时报告（`[PVZCE/对话]` 日志 + 控制台提示）。

### 背包与卡池

玩家的**背包**（`saves/<world>/profile.dat`）决定他能自选哪些卡：新世界默认只有豌豆射手 + 铲子，资源卡（阳光）永远可用。于是：

```
实际卡组 = 关卡的 slots（固定，无视背包） + 玩家从「背包已解锁卡 − 固定卡」里选，填到总卡槽数
总卡槽数 = 关卡写了 max_seed_slots ? 关卡值 : 背包卡槽数（默认 8，上限 12）
```

- 固定卡数 ≥ 总卡槽数时玩家没有可选空间（原版 1-1 就是这样），选卡页放完僵尸预览后会自动开始。
- 想让玩家有选择空间：让总卡槽数大于 `slots.size()` 即可；留空 `slots` 则完全由背包决定。
- **`max_seed_slots` 写与不写是两种意思**：写下的数字是"这一关就是这么多格"（1-1 的 2、1-4 的 8 都是关卡设计），不写是"按玩家的背包来"。所以关卡可以放心省略它，扩容过的背包不会被某个默认值压回去。
- 背包的卡槽数只有服务端改得了：`/profile slots <n>`（1~12）。商店以后走同一个入口（`PlayerProfile.addSeedSlots`），界面上的数字来自 `ProfileS2C`。
- 解锁卡走 `rewards.first_clear`；创造沙盒世界可用「创建世界」对话框的**全解锁**开关。

### 关卡机制（mechanics）

关卡可以声明若干**机制**（`mechanics` 数组，`{"type": "...", ...}`）。都是可选的：

```jsonc
"mechanics": [
  // 传送带：卡组由关卡自己发，没有选卡界面、没有价格（1-5 坚果保龄球）
  { "type": "pvzce:conveyor", "interval_ticks": 150, "capacity": 6, "initial_cards": 2,
    "cards": [ { "id": "pvzce:bowling_nut", "weight": 1 } ] },
  // 可种植区：只有这块草坪能种（红线画在它的边缘）
  { "type": "pvzce:placement_zone", "min_x": 0, "max_x": 3 },
  // 小推车：这一关哪些行有（见下）
  { "type": "pvzce:mower", "rows": [] },
  // 关卡自带的工具：空手点击就用它（2-5 的鼠标就是木槌）
  { "type": "pvzce:tool", "tool": "pvzce:hammer", "default": true,
    "cooldown": 0, "cost": { "resources": {} } },
  // 会补的墓碑：僵尸从墓碑里冒出来（2-5 打地鼠）
  { "type": "pvzce:grave_spawner", "zombies": ["pvzce:basic_zombie"],
    "min_graves": 9, "initial_graves": 9, "graves_per_wave": 1,
    "interval": 90, "min_x": 4, "max_x": 8 }
]
```

**关卡自带的工具（`pvzce:tool`）**：`tool` 是 `tools/<id>.json` 里的工具 id；`default: true` 表示手上没卡时点击就是用它（不占卡槽、不印价格、没有充能条）；`cooldown` / `cost` 是**这一关对这把工具的覆盖**，不写就沿用工具自己的数。工具自己的美术（卡片贴图 `texture` 与动画 `animation_dir`）写在工具定义里，动画文件里要有对应的 clip（木槌的 `idle` = 举起、`attack` = 挥下）。

**会补的墓碑（`pvzce:grave_spawner`）**：`zombies` 是墓碑能冒出什么；`interval` 是两次冒怪之间隔多少 tick（从**随机一座**在场的墓碑里放一只）；`min_graves` 是维持的座数，`initial_graves` 是开局先补到多少（`-1` = 同 `min_graves`）；`graves_per_wave` 让**每来一波目标座数 +1**（2-5 用 1：9 座起步、第六波 15 座；不写就是恒定）；`min_x` / `max_x` 限定新墓碑出现的列。注意两件事：这一关的 `waves` 通常是**空壳**（`entries: []`，只用来算进度与收尾，僵尸全部来自墓碑），而**最后一波放完之后墓碑不再冒僵尸** —— 胜利判定要的是"场上没有僵尸"，每 1.5 秒冒一只的话这一关永远打不完。

**小推车（`pvzce:mower`）——不写就是每行一辆。** 普通关卡的 JSON 里**不需要**任何声明：草坪本来就是每行一辆推车，僵尸走到房子前会触发它，它向右开过去碾掉该行地面上的僵尸，然后消失；那一行之后就是敞开的。想改的关卡才声明：

| 写法 | 含义 |
|---|---|
| 不写 `mechanics` 里的 mower | 每行一辆（默认） |
| `{ "type": "pvzce:mower" }` | 同上，显式写出来（编辑器/文档用） |
| `{ "type": "pvzce:mower", "rows": [0, 4] }` | 只有第 0 行和第 4 行有 |
| `{ "type": "pvzce:mower", "rows": [] }` | 一辆都没有（原版坚果保龄球就是这样） |

细节：只有**地面层**的僵尸会触发与被他碾（气球僵尸飞过、矿工在地下时都不受影响，但它们走到房子里照样算输）；碾压**无视护甲**（铁桶也是碾一下就死）；每行的车用掉就不再回来，并且会随关卡存档一起保存。

**也可以手动放车**：鼠标停在还停着的车身上按住约半秒就会把它放出去（松开取消）。和自动触发的车完全一样——同样的音效、速度与消耗，所以提前放掉一辆就是**这一行后面都敞开着**，请当成一次性资源来用。

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

关卡选择页的两级导航：左侧列是**主题**，顶部行是**类别**。两者各是一份数据文件，**没有 `name`**：
显示名与植物/僵尸一样走语言文件，key 按 `GuiLang` 的 `<ns>.<path>` 规则生成（`pvzce:yard` → `"pvzce.yard"`），
缺失时回退到 id 的最后一段。

```jsonc
// data/mymod/pvzce/level_themes/redstone.json
{ "id": "mymod:redstone", "order": 10 }

// data/mymod/pvzce/level_categories/minigame.json
// trophy: 通关这一类别下的关卡会在列表行右侧得一个奖杯（原版迷你游戏的金杯）
{ "id": "mymod:minigame", "order": 1, "trophy": true }
```

- `order` 决定页签顺序（小的在前，相同按 id 排序）；**未分类**页永远排在最后。
- `trophy`（缺省 `false`）是类别的性质，不是关卡的性质：它说的是"这一类关卡通关算一枚奖章"，
  所以同一类别下**所有**关卡共用同一个判定，新加一关不用再声明。奖杯读的是**通关过至少一次**
  这件事本身（`LevelInfo.cleared`），所以"已通关 + 又开了一局中途退出"时列表行虽然显示"进行中"，
  奖杯不会掉。
- 一个类别只声明一次，可以被任意多个主题使用；某个主题下出现哪些类别，由该主题下**实际存在
  的关卡**决定（见 [levels](#levels)），所以不需要、也没有「主题 → 类别」的声明字段。
- 一个关卡若引用了未注册的主题/类别，会被归入未分类页，并在 `/reload` 时输出原因。

---

## resources / slots / tools / scene_elements

- `resources`：`id` / `default_value` / `collectible` / `icon` / `drop_anim` / `max_stack` / `collectible_without_card` / `tint`（可选，`[r, g, b]` 乘色，默认 `[0.5, 0.5, 0.5]`；阳光用暖黄 `[0.58, 0.50, 0.15]` 让两层叠加光晕不发白）
- `slots`：`id` / `kind`(`plant`|`resource`|`tool`) / `content` / `cost` / `icon`（可选；缺省走 `EntityArt.sprite`，即定义的 `texture`，再回退 `textures/entities/<content>`）

### 波次出怪间隔（`spawn_interval`）

```jsonc
"waves": [
  { "type": "small", "delay": 1500, "entries": [...], "spawn_interval": 480 },  // 8 秒一只
  { "type": "final", "delay": 1500, "entries": [...], "spawn_interval": 150 }   // 2.5 秒一只
]
```

写在**每一波**上：简单关卡的前期可以慢、最终波必须快，这是两个独立的旋钮。缺省 300 tick（5 秒），下限 15 tick。

`delay` 是**两波之间的间隔**：它从**上一波把僵尸放完**的那一 tick 开始算，而不是从上一波触发时算
（`tickWaves` 在还有僵尸没放完时冻结这个计时）。所以 `(僵尸数-1) × spawn_interval ≤ delay` 是
「下一波不要插进上一波的出怪窗口」的写法，而不是硬性要求：写小了会让两波的出怪队列同时跑，
玩家看到的是两波的僵尸混在一起，`LevelServer` 不会拦。

### 开局两波：等上一只死（`hold_until_dead`）

```jsonc
"waves": [
  { "delay": 1500, "entries": [ { "id": "pvzce:basic_zombie", "count": 3 } ] },          // 缺省：等
  { "delay": 1500, "entries": [ ... ], "hold_until_dead": 600 },                          // 换封顶值
  { "delay": 1500, "entries": [ ... ], "hold_until_dead": 0 }                             // 关掉，回到 spawn_interval
]
```

**每一关的前 2 波，只要类型是 `small`，就不按 `spawn_interval` 出怪**，而是"上一只死了就出下一只"，
最多等 1200 tick（20 秒）——超时照出，不拖住关卡。它替代 `spawn_interval`（不是叠加）：玩家快，节奏就快；
玩家慢，僵尸就一只一只来。第 2 波也会等第 1 波清完（同样 20 秒封顶），所以开局不会两头一起冒——**但那只扣住"已经到点"的到达**：延迟照常跑，玩家清得早完全按关卡节奏走，清得晚也只在大波该来时场上还有僵尸的情况下才被推迟，且最多 20 秒。

| 写法 | 含义 |
|---|---|
| 不写 | 前 2 波里的 `small` 波自动如此；`huge`/`final` 与后面的波按 `spawn_interval` |
| `"hold_until_dead": <tick>` | 换成这个封顶值 |
| `"hold_until_dead": 0` | 完全不门控，回到纯 `spawn_interval`（前 2 波也一样） |

「不写」与「写 0」是两件事，所以字段是可选的整数；想给一个自己写清楚节奏的关卡关掉它，就写 0。
门控是**滚动**的：第 3 只等第 2 只，而不是等整波清空。

### 粒子大小（`scale`）

`scale` 就是**画出来的边长，单位是世界格**（引擎里 `size = particle.scale`），不是"相对贴图的倍率"。
它来自原版的 `ParticleScale`，而原版那个值约等于 `精灵像素 / 80`——僵尸手臂 26px → 0.325、星爆 8px → 0.1，
两个都对得上，所以照抄原版的数值一般就是对的**尺寸**。

例外是原版那几件**独立的一整块抛掷物精灵**：它们在自己的画布上带完整轮廓，比僵尸身上对应的部件大得多
（`ZombieHead.png` 64×61，而僵尸模型里的头贴图只有 53×48）。照抄会让一颗头盖住半块草坪，
所以本项目的 `pvzce:zombie_head` 用 0.4、`pvzce:zombie_arm` 用 0.33。

### 粒子的大小变化（`scale_curve`）

`scale_curve` 是**一条随时间变化的尺寸表**（`[[进度, 世界格], …]`，进度 0~1），画出来的边长是
`scale × scale_curve(进度)`：

```jsonc
// 原版 Pow.xml 的 ParticleScale 是 `.2 .5,7`：从 0.2 格长到 0.5 格
"scale": 0.2,
"scale_curve": [[0.0, 0.2], [1.0, 0.5]]
```

原版把「范围」和「曲线」写在同一串数字里（`[.7 .9]`、`.5,60 0`、`.2 .5,7`），
所以**转换器只认得清无歧义的那些**：`[a b]` 与开头的 `a b` 会变成一条从 a 到 b 的直线，
带时间或曲线类型的（`,7`、`,60 0`、`EaseIn`）一律保持常数尺寸。
要让某个效果真的膨胀/收缩，就在定义里写一张显式的表——灰烬类那个爆炸闪光（`pvzce:pow`）
就是手写的，因为原版那条写着 `,7`，转换器读不出它的时间轴。

### 粒子的地面摩擦（`ground_friction`）

`bounce: true` 的粒子落地后水平速度会乘上它（0~1，默认 **1** = 完全不减速）。
默认值刻意保留旧行为：引擎过去落地只把 `vy` 归零、`vx` 一个字节都不动，所以带 bounce 的
投掷物落地后会一直横着滑到自己寿命结束——僵尸头颅因此会一路滑出屏幕左边。
要做"原地掉落、原地消失"就把它调小（`pvzce:zombie_head` 用 0.25）。

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
- **`levels` 的卡池字段**：`slots` 是**关卡自己的卡** —— 进入关卡必定发放，玩家不能取消。`max_seed_slots` 是**总卡槽数**；它减去 `slots` 的数量就是玩家能自选的格数（写得比 `slots` 少时会自动抬到 `slots` 的长度）。**不写 `max_seed_slots` 就是"跟随玩家背包"**（新世界 8 格），所以关卡既可以钉死自己的格数，也可以把这件事交给玩家的背包。
- **`levels` 的小推车**：不用写任何东西——每行默认就有一辆。要改成部分行或干脆没有，才在 `mechanics` 里写 `{"type":"pvzce:mower","rows":[...]}`。
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

`plant`、`zombie`、`projectile`、`resource`、`slot`、`tool`、`scene_element`、`liquid`、`game_rule`、`env_var_type`、`sound_event`、`level`、`damage_type`、`plant_capability`、`zombie_capability`、`projectile_capability`

复数别名（`plants`、`zombies`、`scene`、`levels`…）同样被接受。

---

## damage_types

伤害类型：一次命中**吃不吃护甲**，由内容声明的类型决定，而不是由代码里调了哪个方法决定。
目录是 `data/<ns>/damage_types/<名字>.json`，文件只有两个字段。

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| id | Identifier | 文件路径 | |
| ignores_armor | bool | false | `true` = 直接打身体（灰烬类、小推车）；`false` = 僵尸的护甲能力先接（豌豆、保龄球） |

内置五种：`pvzce:ash`（灰烬类爆炸，`ignores_armor`）、`pvzce:splash`（投手溅射，`ignores_armor`）、`pvzce:mower`（小推车与锤子，`ignores_armor`）、`pvzce:projectile`（普通子弹）、`pvzce:impact`（保龄球、巨人拳）。

```jsonc
// data/mymod/damage_types/poison.json
{ "id": "mymod:poison", "ignores_armor": true }
```

之后在能力里写 `"damage_type": "mymod:poison"` 即可。**未注册的 id 会回退到 `pvzce:projectile`**（即护甲照常生效），
所以拼错的类型不会变成"无视护甲的外挂"；`LevelValidator` / 加载日志会把未知取值报出来。

引用的地方：`pvzce:explosive.damage_type`、`pvzce:splash.damage_type`。

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
