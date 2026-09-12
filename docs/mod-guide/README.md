# PVZCE Mod 开发指南

> 适用版本：PVZCE 1.0.0 第二阶段。
> 本仓库的 `pvzce-mod-template/` 是可直接复制的空模板；产物放入游戏目录 `mods/` 即可加载。

## 1. 工程搭建

```gradle
plugins { id 'java' }

repositories {
    mavenCentral()
    maven { url 'https://maven.pvzce.dev/releases' }
}

dependencies {
    implementation 'com.pvzce:pvzce-game:1.0.0'    // com.pvzce.api + 公开内容定义
    implementation 'com.pvzce:pvzce-loader:1.0.0'  // net.fabricmc.loader/net.fabricmc.api
    implementation 'com.pvzce:pvzce-api:1.0.0'
}

jar {
    from('src/main/resources') // 必须包含 fabric.mod.json / assets / data
}
```

在仓库内联调时也可直接依赖子模块（见 `pvzce-mod-template/build.gradle`）。

## 2. fabric.mod.json

```jsonc
{
  "schemaVersion": 1,
  "id": "example_mod",
  "version": "1.0.0",
  "name": "Example Mod",
  "environment": "*",
  "entrypoints": {
    "main":   ["com.example.ExampleInit"],       // 静态注册表注册
    "client": ["com.example.ExampleClientInit"], // 客户端渲染/键位扩展
    "server": ["com.example.ExampleServerInit"], // 服务端逻辑
    "pvzce.modmenu": ["com.example.ExampleModMenu"] // 可选：配置页/模组菜单信息
  },
  "depends": { "pvzce": "*" },
  "custom": {
    "pvzce": {
      "modmenu": { "badges": ["library"] }
    }
  }
}
```

- `main` 对应 Fabric 的 `ModInitializer`，用于 `Registry.register(BuiltInRegistries.*)`。
  **入口点在数据包加载之前执行**，所以在这里静态注册内容不会撞上"注册表已冻结"；注册成功会触发
  `RegistryEntryAddedCallback`（`registerStatic` 与 `Registry.register` 行为一致）。
- `client` 对应 `ClientModInitializer`。
- `server` 对应 `DedicatedServerModInitializer`，同样在数据加载之前执行。
- `pvzce.modmenu` 是 PVZCE 扩展入口，类型为 `com.pvzce.client.gui.mods.ModMenuApi`。

## 3. 资源与数据目录

```
src/main/resources/
├─ assets/<ns>/textures/...          # 纹理；缺纹理时 WARN + 红橙占位
├─ assets/<ns>/textures/entities/<entity_path>/*.png  # 控制器部件贴图（可再分层）
├─ assets/<ns>/animations/<entity_path>.json         # flipbook / controller 动画
├─ assets/<ns>/textures/particles/*.png              # 粒子贴图
├─ data/<ns>/particles/<类目>/*.json                 # 粒子定义
├─ assets/<ns>/sounds.json + .ogg    # MC 格式 sounds.json
├─ data/<ns>/plants/*.json
├─ data/<ns>/zombies/*.json
├─ data/<ns>/projectiles/*.json
├─ data/<ns>/levels/<主题>/<类别>/<关卡名>.json # 关卡；id 的前两段路径就是主题与类别
├─ data/<ns>/level_themes/*.json               # 主题（关卡选择页左侧列）
├─ data/<ns>/level_categories/*.json           # 类别（关卡选择页顶部行）
├─ data/<ns>/resources/*.json
├─ data/<ns>/scene_elements/*.json
├─ data/<ns>/slots/*.json
├─ data/<ns>/tools/*.json
├─ data/<ns>/sound_events/*.json
├─ data/<ns>/tags/<注册表>/*.json            # 单数(plant)与复数(plants)都可
└─ data/c/tags/<注册表>/*.json               # 约定标签 #c:xxx（放置规则读它）
```

- **能种在哪由标签决定**：给瓦片/植物打上 `#c:ground`、`#c:plantable`、`#c:water`、
  `#c:carrier`、`#c:requires_ground`… 就完成了放置规则，不需要改代码。完整清单与矩阵见
  [json-reference.md](json-reference.md#约定标签c-命名空间)。

- 内容 id 由**完整相对路径**决定：`plants/tier1/pea.json` → `<ns>:tier1/pea`。同一注册表内 id
  冲突会在加载时报错，而不是静默覆盖。
- **关卡的 id 就是它的分类**：`levels/yard/adventure/1_1.json` → `pvzce:yard/adventure/1_1`，
  主题 = 第一段（`yard`）、类别 = 第二段（`adventure`）、其余是关卡自己的名字。主题与类别
  各自是注册表里的一条定义（`{"id":"pvzce:yard","order":0}`），主题的类别列表由**实际存在的
  关卡**推出来——所以加一批关卡不需要再声明页签，也不会出现打开是空的分类页。id 不足两段
  （如 `levels/demo_level.json`）的关卡落在「未分类」页；引用了不存在的主题/类别也会落到那里，
  并在 `/reload` 时把原因打进日志。
- 数据包目录可直接放进游戏目录 `datapacks/<pack>/`，`/reload` 后立即生效。
- 动画资源格式与统一播放 API 见 [animation.md](animation.md)。

## 4. 最小内容示例

`data/example/pvzce/plants/example_flower.json`：

```jsonc
{
  "id": "example:example_flower",
  "cost": { "resources": { "pvzce:sun": 50 }, "cooldown": 300 },
  "health": 300,
  "capabilities": [
    { "type": "pvzce:producer", "resource": "pvzce:sun", "amount": 25, "every": 1440 }
  ]
}
```

行为用 **capabilities** 组合声明；一个植物可以同时带多个能力（例如既能射击又能产阳光）。
字段清单见 [json-reference.md](json-reference.md)。

关卡 JSON 的 `slots` 中加入 `"example:example_flower"` 即可出现在卡槽（推荐同时提供
`data/example/pvzce/slots/example_flower.json`，把卡面图标与费用和植物本体解耦）。

## 5. 常用调试命令

```
/pvzce registry list <category>
/level load <id> · /level rules
/gamerule <id> [value]
/resource give <team> <resource> <amount>
/spawn <plant|zombie|projectile> <id> [x] [y]
/editor open <level>
/reload /save /stop
```

## 6. 配置页接入

```java
public class ExampleModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            ConfigBuilder builder = ConfigBuilder.create(parent.client());
            // parent 是 com.pvzce.client.gui.Screen；通过 client() 可访问窗口/配置
            return builder.build();
        };
    }
}
```

更完整的音量示例见游戏本体 `PvzceClient#buildSettingsConfig()`。

## 7. 已知边界

- 无 remap、无 mixin、无访问宽限；mod 直接编译于公开 API。
- 动画支持 flipbook 与自研 2D 控制器两种后端，统一 `Animatable.playAnimation(state)`；详见 [animation.md](animation.md)。
- 更新检查、模组图标自动缩放、父子展开动画等 ModMenu 高级能力后置。
