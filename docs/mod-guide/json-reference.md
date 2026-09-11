# PVZCE JSON 参考

数据目录约定：`data/<ns>/pvzce/<注册表目录>/<相对路径>.json`。
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
| placement | PlacementDef | `plantable` | `feet`: `plantable`/`ground`/`lily`/`plant`；`count` |
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

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Identifier | |
| name/description | String | 关卡列表显示 |
| width/height | int | 默认 9×5；**大于默认值时实体网格会跟随关卡尺寸** |
| scene | map<Identifier, String[]> | 元素 id → `"x,y"` 列表；畸形/越界条目会被忽略并记录 |
| teams | TeamDef[] | id/name/win_condition |
| win_team | Identifier | 胜利目标队伍 |
| rules | map<Identifier, Json> | 规则覆盖；未知规则或非法取值会在加载时报告 |
| env_vars | map<Identifier, EnvValue> | type/value |
| waves | WaveDef[] | 见下方波次说明 |
| wave_interval_end_multiplier | float | 可选，默认 1.0；线性递减波次间隔 |
| slots | Identifier[] | 可选卡池（引用 slots 注册表）；赛前选卡从该列表中选 |
| max_seed_slots | int | 可选，默认 6；本关最多能带入关卡多少张卡 |
| unlock_resources | map<Identifier, bool> | 资源收集门槛；仍需卡槽中有对应资源卡才能收集 |
| initial_sun | int | 初始阳光 |
| music | LevelMusicDef? | 默认 grasswalk 循环；`cues`: `[{at_tick, track, event, loop, stop, volume, fade_seconds}]` |
| initial_entities | InitialEntityDef[] | 编辑器预摆：kind/id/x/y |

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

## resources / slots / tools / scene_elements

- `resources`：`id` / `default_value` / `collectible` / `icon` / `drop_anim` / `max_stack` / `collectible_without_card`
- `slots`：`id` / `kind`(`plant`|`resource`|`tool`) / `content` / `cost` / `icon`（可选，缺省回退 `textures/entities/<content>`）
  - `content` 指向真正的植物/工具/资源 id。**关卡卡池引用的是 slot id**；若没有对应的 `slots/*.json`，也可以直接写植物 id（兼容路径）。
- `tools`：`id` / `use_cost` / `cooldown` / `targets`(`cell`|`plant`|`zombie`) / `effect` / `uses`（`-1` 为无限次）
- `scene_elements`：`id` / `surface`(`GRASS`|`GROUND`|`WATER`|`LILY`|`ROOF`|`ROOF_SLOPE`|`GRAVE`|`CRATER`) / `accepts[]` / `max_height` / `liquid`(可选，指向一个液体 id)
- `liquids`：`id` / `base_texture` / `shallow_color` / `deep_color` / `opacity` / `depth_scale` / `foam{color,width}` / `wave{speed,amplitude,density}` / `caustics` / `reflect_color` / `fresnel` / `specular` / `specular_power` / `static_frames`（全部可选，颜色用 `#RGB`|`#RRGGBB`|`#RRGGBBAA`）。详见 [rendering.md](rendering.md#水面液体)

---

## 标签（tags）

目录：`data/<ns>/tags/pvzce/<注册表目录或注册表 id>/<名字>.json`，**单数（`plant`）与复数（`plants`）两种拼写都接受**。

```jsonc
{ "replace": false, "values": [ "pvzce:pea_shooter", "#other:group" ] }
```

引用不存在的注册表条目会在加载时报告。

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
