# PVZCE 动画系统

> 当前实现：**flipbook + 自研 2D 控制器** 两种后端；每个实体同时只播放一个动画，但调用方式统一。
> 旧的 `AnimationDef` / `ANIMATIONS` 数据注册表已删除；动画资源改为 `assets` 资源包。

## 资源位置

| 内容 | 路径 |
|---|---|
| 每实体动画文件 | `assets/<ns>/animations/<animation_dir>/<entity_path>.json`（`animation_dir` 缺省＝id 路径） |
| 控制器部件贴图 | `assets/<ns>/textures/entities/<任意子目录>/<entity_path>/*.png`（路径写在动画 JSON 的 part 里） |
| 静态首帧回退 | 定义里的 `texture` 字段；缺省为 `assets/<ns>/textures/entities/<entity_path>.png` |

实体路径来自 `defId`，例如 `pvzce:pea_shooter` → `assets/pvzce/animations/pea_shooter.json`。
`PlantDef` / `ZombieDef` / `ProjectileDef` 可选覆盖：

```json
{
  "animation": "mymod:custom_pea_shooter",
  "animations": {
    "walk": "mymod:custom_walk"
  }
}
```

解析顺序：`animations[state]` → `animation` → 路径约定。没有资源时显示 `<entity_path>.png` 静态首帧，不再自动使用 `_2.png`。

## 自动识别

JSON 根节点写 `"type": "flipbook"` 或 `"type": "controller"`。
缺省时：有 `model` / `animations.*.bones` 判为 controller，有 `animations.*.frames` 判为 flipbook。

## Flipbook

```json
{
  "type": "flipbook",
  "size": [0.7, 0.95],
  "anchor": [0.5, 0.0],
  "animations": {
    "walk": {
      "frames": [
        "pvzce:textures/entities/basic_zombie",
        "pvzce:textures/entities/basic_zombie_2"
      ],
      "delays": [0.25, 0.25],
      "loop": true,
      "transition": 0.05
    }
  }
}
```

- `frames`：Identifier 列表，省略 `.png`；`delay` 标量或 `delays` 数组，单位秒。
- `size`：世界格；`anchor`：0..1，默认 `[0.5, 0.0]`（脚底中心）。
- `loop`、`on_end: hold|idle|next`、`next`、`transition`（秒）。
- `sound_effects` / `particle_effects` / `timeline` 见事件轨。

## 2D 控制器

```json
{
  "type": "controller",
  "model": {
    "bones": [
      {"name": "root", "parent": null, "pivot": [0.0, 0.0]},
      {
        "name": "head",
        "parent": "root",
        "pivot": [0.0, 0.0],
        "parts": [
          {
            "texture": "pvzce:textures/entities/pea_shooter/head",
            "uv": [0, 0, 70, 65],
            "size": [0.70, 0.65],
            "offset": [0.0, 0.0],
            "z": 0
          }
        ]
      }
    ]
  },
  "animations": {
    "idle": {
      "animation_length": 2.0833333333,
      "loop": true,
      "transition": 0.1,
      "bones": {
        "head": {
          "translation": {
            "0.0": [0.1, 0.2],
            "0.5": {"vector": [0.12, 0.22], "easing": "easeInOut"}
          },
          "rotation": {"0.0": [0.0, 0.0, 3.0]},
          "scale": {"0.0": [1.0, 1.0]},
          "visible": {"0.0": true}
        }
      }
    }
  }
}
```

- 模型单位 = 世界格，x 右、y 上；根骨骼原点 = 脚底中心。
- `parts[].uv` 用左上角像素矩形；`size` 用格；部件以 `offset` 为中心。
- 关键帧通道：`translation` `[x,y,(z 忽略)]`、`rotation` `[x,y,z]` 度、`scale` `[x,y,(z 忽略)]`、`visible` bool。
- `rotation` 的 x/y 映射 PvZ reanim 的 `kx/ky` 斜切，z 为平面旋转；这样可完整还原 reanim。
- 关键帧值可以是数组，也可以是 `{"vector":[...],"easing":"linear|step|easeIn|easeOut|easeInOut"}`；缺省线性。
- `animation_length` 省略时取最大关键帧时间；`loop` / `on_end` / `next` / `transition` 同 flipbook。

## 事件轨

```json
{
  "sound_effects": {
    "0.5": {"effect": "pvzce:sfx/plant/chomp", "volume": 1.0, "pitch": 1.0}
  },
  "particle_effects": {
    "0.5": {"effect": "pvzce:hit_spark", "locator": "head"}
  },
  "timeline": {
    "0.5": "mymod:callback"
  }
}
```

- `sound_effects`：到时间点播放客户端音效。
- `particle_effects`：到时间点生成粒子；`locator` 指向骨骼名，缺省为根骨骼/锚点。
- `timeline`：当前只解析不执行，保留给模组回调。

## 调用 API

```java
// ClientEntity implements Animatable
entity.playAnimation("idle");   // 同状态幂等，不会重播
entity.playAnimation("shoot");
entity.stopAnimation();
entity.currentAnimation();

AnimationHandle handle = entity.playAnimation("shoot");
handle.progress();
handle.restart();
handle.stop();
handle.setSpeed(1.5F);
```

- `on_end: idle` 的内部切换不会改变 `requestedState`，下一帧重复调用 `playAnimation("shoot")` 不会覆盖。
- 世界动画时间来自 `DebugInfoS2C` 外推的游戏秒（60 游戏 tick = 1 游戏秒）；暂停、单步、`/tick rate`、冲刺都会影响世界动画。
- UI 目标后续接入同一 `Animatable` 接口，使用墙钟。

## 已接入原版 reanim 的实体

以下实体已由 `refer/anim` 下的原版 reanim 转成控制器 JSON：

| 类别 | 实体 |
|---|---|
| 资源掉落 | 阳光（`Sun.reanim`，旋转循环） |
| 植物 | 豌豆射手、向日葵、樱桃炸弹、坚果墙、土豆雷、大嘴花、玉米投手、金盏花、睡莲、花盆、咖啡豆、寒冰射手、双发射手、三线射手、分裂豌豆、机枪射手、仙人掌、卷心菜/西瓜/冰西瓜投手、火爆辣椒、毁灭菇、窝瓜、小喷菇 |
| 僵尸 | 普通僵尸、路障/铁桶/铁门（共用 `Zombie.reanim`）、读报僵尸、撑杆僵尸、气球僵尸、矿工僵尸、巨人僵尸、僵王博士、小鬼僵尸 |

- 控制器资源默认位于 `assets/pvzce/animations/<entity_path>.json`，部件贴图位于 `assets/pvzce/textures/entities/<entity_path>/`。**内置包两者都按类目分层**（`animations/plant/attacker/pea_shooter.json`、`textures/entities/plant/attacker/pea_shooter/`），所以内置内容的定义里写了 `"animation_dir": "plant/attacker"` 与 `"texture": "…"`；**mod 两个字段都不写也照常工作**。
- `model.size` 是实体的参考视觉尺寸（世界格），阴影和未来 UI 会读取它；巨人僵尸、僵王博士比普通僵尸大，小鬼僵尸比普通僵尸小。
- 服务器状态字符串直接作为 clip 名；例如 `idle/walk/eat/hit/death`、`shoot`、`explode`、`grow/armed`、`chew`、`hammer`、`jump`、`dig/dig_exit`、`fly/fall`、`sleep`（蘑菇白天睡觉）等。**能力按名字要哪个 clip，美术就得有哪个**：缺一个不会报错，只会静默回退到 `idle`。
- 子弹（pea/kernel/melon/butter/puff）没有对应 reanim，仍使用静态/贴图回退。

重新生成全部已接入实体：

```bash
python3 tools/reanim_to_pvzce_all.py

# 从控制器 idle/armed 姿态重新渲染 128x128 植物卡面
python3 tools/render_controller_icons.py
```

卡槽图标由 `SlotDef.icon` 配置，默认已指向 `assets/<ns>/textures/gui/cards/<entity>.png`；要换成自己的卡面，改对应 slot JSON 的 `icon` 即可。

也可以只生成单个实体：

```bash
python3 tools/reanim_to_pvzce_all.py --entity sunflower
```

豌豆射手的旧转换脚本仍保留：

```bash
python3 tools/reanim_to_pvzce.py
```

## 开发冒烟

冒烟参数走 **`-Ppvzce.smoke=\"…\"`**（Gradle 属性），不是 `JAVA_TOOL_OPTIONS`／`-D`：
`run` 任务会把它们转成游戏 JVM 的系统属性，而 `-D` 只到 Gradle daemon、进不了 fork 出来的进程。

```bash
# 标题页 30 帧
./gradlew :pvzce-game:run -Ppvzce.gameDir=/tmp/pvzce-smoke \
    -Ppvzce.smoke="pvzce.smokeFrames=30"

# 自动进入关卡跑 900 帧，验证实体动画渲染不崩，并在第 890 帧存一张图
./gradlew :pvzce-game:run -Ppvzce.gameDir=/tmp/pvzce-smoke \
    -Ppvzce.smoke="pvzce.smokeLevel=pvzce:yard/adventure/1_1 pvzce.smokeFrames=900 \
        pvzce.captureFrame=890 pvzce.capturePath=/tmp/animation.png"
```

两件必须知道的事：

- **`-Ppvzce.gameDir` 一定要带**，否则这次运行会写进真正的 `~/.pvzce`。
- **`smokeLevel` 受解锁门槛约束**：用锁着的关卡（例如全新世界的 `1_2`）会静默失败并停在标题页。
  上面用 `1_1` 就是因为它是默认解锁的那一关。

完整参数表、坑与典型组合见 `../冒烟与截图指南.md`。

> 若使用本仓库沙箱环境，可用 `GRADLE_USER_HOME=$PWD/.gradle-user` 并直接调用 Gradle 发行版；普通环境用 `./gradlew` 即可。
