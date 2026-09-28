# pvzce-loader

PVZCE 的模组加载器。**fork 自 [Fabric Loader](https://github.com/FabricMC/fabric-loader) `0.19.5`**，
裁掉 Minecraft 专属部分，接上 PVZCE 自己的游戏入口。

## 许可（重要）

**本目录中的绝大部分代码版权归 FabricMC，采用 Apache License 2.0** —— 原文见同目录
[`LICENSE`](LICENSE)，文件头注释模板见 [`HEADER`](HEADER)。

**PVZCE 自行新增的部分采用 GPL-3.0**（见仓库根 `LICENSE`）。目录级的完整说明在仓库根的
[`THIRD-PARTY.md`](../THIRD-PARTY.md) §2；这里再写一遍是因为 Apache-2.0 的归属声明必须
**跟着代码走**——只 clone 或只复制这个子目录的人，看不到仓库根的那份文档。

## 相对上游改了什么

改动极小，**没有修改任何上游源文件的内容**：

| 位置 | 改动 |
|---|---|
| `src/main/java/`（139 个文件） | 与上游**逐字节一致** |
| `src/main/legacyJava/`（20 个文件） | 与上游**逐字节一致** |
| `src/main/resources/.../Messages.properties`、`Messages_zh_CN.properties` | 各追加 2 行 `exception.pvzce.*` 文案键 |
| `src/main/resources/META-INF/services/net.fabricmc.loader.impl.game.GameProvider` | **新增**，指向 PVZCE 的 GameProvider |
| `src/main/java/com/pvzce/launcher/` | **新增**（PVZCE 原创，GPL-3.0） |

新增的两个类是：

- `PvzceGameProvider` —— 实现 Fabric Loader 的 `GameProvider` 接口，把加载器的启动流程
  接到 PVZCE 的游戏入口上，替代上游的 Minecraft GameProvider；
- `PvzceVersions` —— 游戏 id / 名称 / 版本的常量，加载器层与游戏层共用。

裁剪体现为**依赖面的收窄**（见 `build.gradle`），而不是改写上游类。上游仓库没有 `NOTICE`
文件，因此 Apache-2.0 §4(d) 的 NOTICE 传播义务不适用。

> 上游 Fabric Loader 是 Minecraft 模组加载器。PVZCE **不是 Minecraft 模组，也不包含任何
> Minecraft 代码**；保留 `net.fabricmc.*` 包名是因为游戏自包含、不存在环境冲突
> （决策记录 D1/D2，见 `docs/00-架构总览与开发路线.md` §0.2）。

## 打包

以 shadow 方式并入游戏本体，最终产物是 `pvzce-game/build/libs/pvzce-1.0.jar`。
打出来的 fat jar 同时含 Apache-2.0 的 Fabric Loader 代码与 GPL-3.0 的 PVZCE 代码——
整包按 GPL-3.0 分发，其中 Fabric 部分的许可原文仍是本目录的 `LICENSE`。
