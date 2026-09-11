# PVZCE 渲染与资源包

## 渲染后端现状（第二阶段）

- **动画后端**：已实现 **flipbook** 与 **自研 2D 控制器** 两种；统一走 `ClientEntity.playAnimation(state)`，按每实体 `assets/<ns>/animations/<entity_path>.json` 自动识别。详见 [animation.md](animation.md)。
- **缺失纹理**：红-橙占位块；控制台输出一次 `WARN  PVZCE/Client - Missing texture reference: <id>`。
- **静态回退**：没有动画 JSON 的实体显示 `<id>.png` 首帧；旧的 `_2.png` 自动切换已移除。当前已接入原版 reanim 的实体见 [animation.md](animation.md)。

## 关卡背景与棋盘映射

- 关卡背景图：`assets/pvzce/textures/gui/screen/level/background1unsodded.png`（已核对原图为 1400×600：左房门、右侧马路、中间 9×5 裸地）。可种植裸地区域为 `x=256..976, y=80..580`，即 720×500，单格 80×100。
- 场景草地纹理 `textures/scene/grass.png` 是 1254×1254 的 6×6 方形格；游戏内/选卡预览按方形像素等比绘制并裁剪到棋盘矩形，再向外多渲染约 0.12 格草边，避免边缘看起来被切掉。植物/僵尸/影子也按同一 `unitY/unitX` 横向补偿，保持原版精灵像素比例；非草地场景元素仍按格子矩形绘制。
- 选卡木质面板原图 `SeedChooser_Background.png` 为 465×513；`NinePatch` 使用按原始像素比例的平铺中段九宫格，不会把木纹横向拉宽。
- 顶部卡槽条中铲子/锤子等工具卡固定显示在最右侧；SunBank 槽不占卡槽条，只在选中时渲染左上 HUD。
- `LevelStage` 负责将背景 cover 到窗口，并把世界坐标中的棋盘等比映射到裸地区域：9×5 关卡正好铺满裸地；其他尺寸保持格子长宽比不变，按统一比例只填满宽或高其中一条轴（允许放大或缩小），另一方向的空白留在右侧（靠房门锚定）或上下（垂直居中），不再为了同时填满两个方向而拉伸。
- `PvzceCamera` 的 viewport 覆盖整个背景，因此右移出生的僵尸会先渲染在马路区域，再走进草坪；鼠标命中和实体投影均使用同一套棋盘坐标。

## 水面（液体）

场景元素只要在定义里写了 `liquid`，就不再按贴图画，而是交给**水面 pass** 渲染。

### 内容怎么加

```jsonc
// data/<ns>/pvzce/scene_elements/water.json
{
  "id": "pvzce:water",
  "surface": "WATER",
  "accepts": ["lily"],
  "max_height": 0,
  "liquid": "pvzce:water"        // 新字段：我要用哪个液体来画
}
```

```jsonc
// data/<ns>/pvzce/liquids/water.json —— 渲染参数的唯一出处，每个字段都有默认值
{
  "id": "pvzce:water",
  "base_texture": "pvzce:textures/scene/water_base",  // 水底，必须可无缝平铺
  "shallow_color": "#4FA8C8C8",   // #RGB / #RRGGBB / #RRGGBBAA 都行
  "deep_color": "#1E6E8C",
  "opacity": 0.68,                // 水占多少、水底露多少（不是 alpha）
  "depth_scale": 1.6,             // 离岸多少格算"深水"
  "foam":  { "color": "#E8F6F6", "width": 0.085 },  // 宽度单位是格
  "wave":  { "speed": 0.055, "amplitude": 0.55, "density": 2 },
  "caustics": 0.6,
  "reflect_color": "#9FC7E8",
  "fresnel": 0.35,
  "specular": 0.45,
  "specular_power": 72,
  "static_frames": 4              // 关闭 shader 时循环几张烘焙帧
}
```

**加一种新液体（岩浆、沼泽、酸液）不需要写代码**：加一个 `liquids/*.json`、加一个带 `liquid` 字段的场景元素，就完了。

### 画质与开关

- `water_quality`（低/中/高，视频设置里可切）：只切换 shader 求值哪些项（低=只有岸线泡沫，中=加波浪与高光，高=全部），**不改变几何**，所以三档的水面形状一样。
- `shaders_enabled = false`：整条水面 pass 换成预烘焙的静态帧（`textures/scene/water_frames/<液体id>_<n>.png`，帧数由 `static_frames` 决定，循环播放）。想换观感就覆盖这些帧。

### 做底图要注意

底图会**按世界格坐标连续平铺**，所以必须**无缝**，否则缝会在水体中间成网格。`tools/gen_water_base.py` 是内置底图的生成器，它从 `refer/im4/underwater.png` 挑一块相邻 2×2 单元、再用周期窗把边缘做成可平铺，并自带接缝的数值验证（要求接缝跳变不大于图像自身的 1 像素步长）。参考图本身**不是**可平铺的——直接拿它当 tile 会在每个接缝出现硬边。

### 涟漪

服务端说"这里发生了什么"，客户端放动画：

```java
level.emitRippleAt(gridX(), gridY(), 1F);              // 扰动该格所在的液体
level.emitRipple(PvzceIds.WATER, x, y, 0.6F);          // 指定液体与强度
```

它走的是 `EffectEventS2C` 的 `ripple` / `ripple_strength` 字段（和粒子、音效同一条表现事件通道），所以将来联机也天然可用。客户端解析不出这个液体 id 时会**忽略**这条涟漪，不会在草坪上画一圈水波。

### 预览与调参（不需要启动游戏）

`tools/preview_water.py` 是 shader 的 CPU 镜像，直接读**发布的数据文件**出图：

```bash
python3 tools/preview_water.py --level combat_test.json --out /tmp/water.png
python3 tools/preview_water.py --level <关卡> --quality low --probe 500,250   # 打印某像素的中间值
python3 tools/preview_water.py --level <关卡> --seams --grid                  # 接缝与逐格判定
```

它靠人工与 shader 同步常量（改 shader 的数值时记得同步），但**参数**（颜色、透明度等）直接从 `liquids/*.json` 读，不会漂。

## 实体纹理约定

| 实体 | 路径 |
|---|---|
| 植物 | `assets/<ns>/textures/entities/<plant_id>.png` |
| 僵尸 | `assets/<ns>/textures/entities/<zombie_id>.png` |
| 子弹 | `assets/<ns>/textures/entities/<projectile_id>.png` |
| 资源掉落 | `assets/<ns>/textures/entities/<resource_id>.png` |
| 场景 | `assets/<ns>/textures/scene/<element_id>.png` |

资源包放入 `resourcepacks/<pack>/`，`/reload` 后按包优先级覆盖。

## 字体

- 图集：`assets/<ns>/font/ui.png` + `ui.json`（line_height + glyphs[x,y,w,h,advance]）。
- `line_height` 是图集的**烘焙行高**（例如内置图集为 128px）。超过 18px 的图集会被按比例缩小到 18px 逻辑行高，因此高分辨率图集不会让文字变大；更小的图集保持原大小。
- `FontRenderer` 会测量像素包围盒、按统一 Latin 基线对齐；中英文混排无需额外基线元数据。

## 声音

- 声明：`assets/<ns>/sounds.json`（MC 格式，事件键如 `sfx/plant/shoot_pea`、`music/grasswalk`）。
- 文件：`assets/<ns>/sounds/music/*.ogg`（音乐）与 `assets/<ns>/sounds/sfx/{plant,zombie,projectile,ui,effect,ambient,misc}/*.ogg`（音效）；STB Vorbis 解码，OpenAL 播放。
- 资源包覆盖：保持相同的事件键与文件路径即可覆盖内置音频。
- 音量：`config/pvzce-client.toml` 的 `master_volume` / `music_volume` / `sfx_volume`。

## 接入未来视觉后端

```java
// 预留形状（第二阶段未开放运行时）：
public interface EntityVisualProvider<S extends PvzceEntity> {
    VisualRenderer<S> createRenderer(int entityId, RegistryAccess dynamic);
}
public interface VisualRenderer<S> {
    void render(PoseStack ms, S entity, float tickDelta, MultiBufferSource bufs);
}
```

Spine/GeckoLib/block 旧规划已收敛；当前动画资源格式与调用方式见 [animation.md](animation.md)。
