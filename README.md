# PVZCE

**一个用 Java 写的独立桌面塔防引擎**，附带一套"植物大战僵尸"风格的示例内容。
它不是 Minecraft 模组，也不修改任何原版游戏进程——它是一个自包含的可执行程序，
内置自己的模组加载器、渲染层、60tps 权威服务端与关卡编辑器。

> **免责声明**：This project is not endorsed by or affiliated with EA or its licensors.
> 本项目未获 EA 或其许可方的认可或关联，与 PopCap Games / Electronic Arts 无任何关系。
> 详见 [第三方组件与版权声明](THIRD-PARTY.md)。

---

## 它是什么

| | |
|---|---|
| **形态** | 独立桌面程序（Java 25 + LWJGL 3），单机或本地联机 |
| **时钟** | 服务端 60 tick/s 权威模拟，客户端只读镜像，两边靠包交换通信 |
| **扩展** | 三层：**模组**（带代码）、**资源包**（贴图/音效/语言/动画）、**数据包**（内容 JSON） |
| **加载器** | fork 自 [Fabric Loader](https://github.com/FabricMC/fabric-loader) `0.19.5`，裁掉 Minecraft 专属部分 |
| **包约定** | `Identifier`、`assets/<ns>/…`、`data/<ns>/…`、注册表机制——有意与 Minecraft 同构 |
| **关卡** | 网格制（默认 5×9）+ 场景元素叠放规则；内置拼装式关卡编辑器 |

模块：

| 模块 | 是什么 |
|---|---|
| `pvzce-loader` | 模组加载器（Fabric Loader fork，Apache-2.0，见该目录 `README.md`） |
| `pvzce-api` | 给第三方模组的稳定 API 面 |
| `pvzce-game` | 引擎本体：服务端逻辑、客户端渲染、内容与关卡 |
| `pvzce-mod-template` | 起一个新模组的骨架工程 |

---

## 跑起来

需要 **JDK 25**。第一次构建会从 Maven Central / FabricMC 拉依赖。

```bash
./gradlew :pvzce-game:run                 # 启动游戏
./gradlew :pvzce-game:test                # 全量单测（约 1 分钟）
./gradlew :pvzce-game:shadowJar           # 打可执行 fat jar → pvzce-game/build/libs/pvzce-1.0.jar
```

> 沙箱/CI 环境里 `~/.gradle` 不可写时，先 `export GRADLE_USER_HOME=$PWD/.gradle-user`。

### ⚠️ 素材要自备，仓库里没有

**本仓库不包含原版《植物大战僵尸》的任何素材**——那些贴图、音效与原声的版权归
PopCap/EA，不能随开源仓库分发（原因见 [THIRD-PARTY.md](THIRD-PARTY.md) §4）。

**没有素材也能启动**：缺贴图的地方会画成紫色棋盘格，逻辑与界面照常运行，单测全绿。

**要有画面**，把原版素材放成资源包即可，引擎本来就支持（游戏目录下 `resourcepacks/` 的优先级
高于内置资源）。开发机上的做法是同仓库根的 `local-assets/`（由 `.gitignore` 排除）：

```bash
python3 tools/classify_assets.py --summary      # 看素材归属分类
python3 tools/exclude_pvz_assets.py --dry-run   # 预览哪些原版素材会被移出工作区
python3 tools/exclude_pvz_assets.py             # 执行移动
```

`local-assets/` 存在时，`stageLocalAssets` 任务会把它接回构建，`run`/`test` 照常带素材跑。
哪些素材算"原版"、逐条依据是什么，见 **[素材对照表](docs/素材对照表.md)**。

自产的素材（原创角色立绘、程序生成的水面与粒子、四份 OFL 开源字体）**都在仓库里**，
所以 clone 下来就能看到界面与菜单，只是游戏内的植物与僵尸是棋盘格。

---

## 文档

文档是分层的，**入口是 [`docs/README.md`](docs/README.md)**（台账：每份文档是什么、
什么时候该改）。想快速了解现状看这三份：

| 想知道 | 看 |
|---|---|
| 代码现在长什么样、往哪扩 | [`docs/当前项目架构.md`](docs/当前项目架构.md) |
| 还没做什么 | [`docs/todo.md`](docs/todo.md) |
| 为什么当初这么定 | [`docs/决策记录.md`](docs/决策记录.md) |

想给这个引擎写模组，看 [`docs/mod-guide/`](docs/mod-guide/)。
版权与许可的完整情况看 [`THIRD-PARTY.md`](THIRD-PARTY.md) 与
[素材对照表](docs/素材对照表.md)；素材处理方式的调研（含同类项目怎么做、EA 的官方原文）
在 [`docs/报告/`](docs/报告/)。

---

## 许可

- **本项目的代码**：GNU General Public License v3.0（[LICENSE](LICENSE)）。

  Copyright (C) 2026 crystalneko

  这一行是 GPL 的版权声明，它在这里而不在 `LICENSE` 里，是因为那个文件是 FSF 的许可原文，
  一个字节都不该改（末节 "How to Apply These Terms" 是给使用者抄的模板，填进正文会让
  许可文件的版本不可辨认）。同一行常量在代码里的单一出处是
  `PvzceLicense.COPYRIGHT`，启动日志与设置里的"关于"页都读它。
- **`pvzce-loader/` 中源自 Fabric Loader 的部分**：Apache License 2.0，
  版权归 FabricMC（[pvzce-loader/LICENSE](pvzce-loader/LICENSE)）。
  Apache-2.0 可单向并入 GPLv3，因此整包按 GPL-3.0 分发，子目录的许可原样保留。
- **四份中文字体**：SIL Open Font License 1.1（[font/OFL.txt](pvzce-game/src/main/resources/assets/pvzce/font/OFL.txt)、
  [font/OFL-zhanku.txt](pvzce-game/src/main/resources/assets/pvzce/font/OFL-zhanku.txt)）。
- **游戏内容素材**：见 [THIRD-PARTY.md](THIRD-PARTY.md) §4 —— **不在上述任何许可范围内**。

本项目**非商业**：不接受赞助、不售卖周边、构建产物不放在任何付费墙后面。
