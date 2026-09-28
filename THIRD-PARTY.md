# 第三方组件与版权声明

本文件说明 PVZCE 仓库中各部分的版权归属与许可，**它是 `LICENSE`（GPL-3.0）的补充，不改变任何第三方
组件的原始许可**。若本文件与某个组件自带的许可原文冲突，以该组件自带的许可原文为准。

---

## 1. 许可范围总表

| 范围 | 许可 | 说明 |
|---|---|---|
| `pvzce-game/`、`pvzce-api/`、`pvzce-mod-template/`、`tools/`、`docs/`、本文件 | **GPL-3.0**（见 `LICENSE`） | 项目作者原创 |
| `pvzce-loader/` 中源自 Fabric Loader 的部分 | **Apache-2.0**（见 `pvzce-loader/LICENSE`） | fork 自 Fabric Loader，保留原许可与版权头 |
| `pvzce-loader/` 中 `com/pvzce/launcher/` 与 `META-INF/services/` 下新增的部分 | **GPL-3.0** | 项目作者原创，见 §2 |
| `pvzce-game/src/main/resources/assets/pvzce/font/` 下的字体 | **SIL OFL-1.1** | 见 §3 |
| 游戏内容素材 | **不属于本项目的许可范围** | 原版素材已移出仓库；二次创作仍在仓库但属衍生作品。见 §4 |

---

## 2. `pvzce-loader/` —— Fabric Loader 的裁剪 fork

**上游**：[Fabric Loader](https://github.com/FabricMC/fabric-loader) `0.19.5`，版权归 FabricMC，
许可 **Apache License 2.0**（原文见 `pvzce-loader/LICENSE`，文件头注释见 `pvzce-loader/HEADER`）。

### 上游文件的处理方式

fork 时**没有修改任何上游源文件的内容**，逐文件比对结论如下：

- `src/main/java/`（139 个文件）与 `src/main/legacyJava/`（20 个文件）：与上游**逐字节一致**。
- `src/main/resources/net/fabricmc/loader/Messages.properties` 与 `Messages_zh_CN.properties`：
  各追加了 2 行 PVZCE 自己的异常文案键（`exception.pvzce.*`）。
- `src/main/resources/META-INF/services/net.fabricmc.loader.impl.game.GameProvider`：**新增**，
  指向 PVZCE 自己的 GameProvider。

### PVZCE 新增的部分

- `src/main/java/com/pvzce/launcher/PvzceGameProvider.java`、`PvzceVersions.java`：PVZCE 原创，
  **GPL-3.0**。作用是把 Fabric Loader 的启动流程接到 PVZCE 游戏入口上，替代上游的 Minecraft
  GameProvider。
- 裁剪掉的 Minecraft 专属部分在 `pvzce-loader/build.gradle` 里体现为**依赖面的收窄**，
  而不是改写上游类。

### 为什么整个仓库可以声明为 GPL-3.0

Apache-2.0 与 GPL-3.0 的兼容是**单向**的：Apache-2.0 的作品可以被并入 GPL-3.0 的作品
（见 [ASF 官方说明](https://www.apache.org/licenses/GPL-compatibility.html)），反向则不行。
因此 `pvzce-loader/` 里的 Fabric Loader 代码以 **Apache-2.0 原样保留**（`pvzce-loader/LICENSE`
与原文件头注释均在），而 PVZCE 自己新增的代码以及整个仓库的整体分发按 **GPL-3.0**。
上游仓库没有 `NOTICE` 文件，因此 Apache-2.0 第 4(d) 条的 NOTICE 传播义务不适用。

> 上游 Fabric Loader 是 Minecraft 模组加载器。PVZCE 不是 Minecraft 模组，也不包含任何
> Minecraft 代码；fork 保留 `net.fabricmc.*` 包名仅仅是因为游戏自包含、不存在环境冲突。

---

## 3. 字体（`assets/pvzce/font/`）

四份字体文件都会被打进发布用的 fat jar，因此随附许可原文与版权声明是**分发时的义务**，不是可选项。

| 文件 | 字体 | 版权 | 许可 | 许可原文位置 |
|---|---|---|---|---|
| `noto_sans_sc_regular.ttf` | Noto Sans SC | © 2014-2021 Adobe | SIL OFL-1.1 | 内嵌于 TTF 的 name 表 + `font/OFL.txt` |
| `noto_sans_sc_medium.ttf` | Noto Sans SC Medium | © 2014-2021 Adobe | SIL OFL-1.1 | 同上 |
| `noto_serif_sc_regular.ttf` | Noto Serif SC | © 2017-2024 Adobe | SIL OFL-1.1 | 同上 |
| `zhanku.ttf` | 站酷快乐体 2016 修订版（HappyZcool-2016） | © LuiBingKe 2016 | SIL OFL-1.1 | **需单独随附**，见下 |

- `font/OFL.txt` 是 Adobe 的 OFL-1.1 原文（`Copyright 2014-2021 Adobe …, with Reserved Font Name
  'Source'`），覆盖三份 Noto。
- **站酷快乐体采用 OFL-1.1 发布**（上游开源版本名为 *ZCOOL KuaiLe*），但 `zhanku.ttf` 的 TTF
  name 表里**没有内嵌许可字段**，`font/OFL.txt` 也没有写它的版权行。OFL-1.1 要求再分发时随附
  许可原文与版权声明，因此这份字体的 OFL 副本与版权行需要单独补齐（见 `font/OFL-zhanku.txt`）。
- OFL-1.1 **允许**把字体打进软件分发与商业使用，条件是：保留版权声明、随附许可原文、
  不得单独售卖字体本身、不得使用保留字体名（Reserved Font Name）命名修改版。

---

## 4. 游戏内容素材 —— **不属于本项目许可范围**

> **这一节是必须读的。** 下面的内容不是项目作者的版权物，**GPL-3.0（以及本仓库任何许可）
> 都不适用于它们**。

### 4.1 原版素材：**不在本仓库里**

从原版《植物大战僵尸》（Plants vs. Zombies）拆包或转换而来的素材，其版权归
**PopCap Games / Electronic Arts** 所有；《植物大战僵尸》与 Plants vs. Zombies 是 EA 的注册商标。
这些素材**已被移出本仓库**，留在开发机本地的 `local-assets/`（由 `.gitignore` 排除）：

| 内容 | 规模 | 来源 |
|---|---|---|
| `animations/`（除 zombotany） | 87 个 JSON | `tools/reanim_to_pvzce_all.py` 由 `refer/anim/*.reanim` 转换 |
| `textures/entities/` | 1424 个 PNG | 同上；原版 reanim 图集的零件被裁成独立 PNG |
| `sounds/music/` | 15 个 OGG | 原版原声（`grasswalk`、`moongrains`、`ultimate_battle`…） |
| `sounds/sfx/` | 167 个 OGG | 原版音效（`chomp`、`awooga`、`readysetplant`…） |
| `animations/zombie/zombotany/` | 4 个 JSON | `tools/make_zombotany_assets.py` 用原版零件拼合 |

逐条判决与依据见 **`docs/素材对照表.md`**（由 `tools/classify_assets.py` 生成，可 `--check` 复查）。
搬运与回滚用 `tools/exclude_pvz_assets.py`（`--dry-run` 预览、`--rm` 搬回）。

**本地为什么还能跑**：`pvzce-game/build.gradle` 的 `stageLocalAssets` 任务在 `local-assets/`
存在时把它作为独立 classpath 根接回构建，所以 `run` / `test` / 冒烟照常带素材；
目录不存在（例如刚 clone）时任务自动跳过，游戏仍能启动，缺贴图处方显示为棋盘格。

### 4.2 二次创作：**在仓库里，但仍是衍生作品**

另一半素材是按原版 IP 形象**重画、改色或复刻版式**而来的（角色立绘、卡面、图鉴、HUD、场景、
标题 logo 等，约 130 MB）。它们由项目作者绘制或生成，代码层面属于本项目；但由于使用了原版的
角色形象与视觉设计，**法律上仍是 EA 知识产权下的衍生作品，不在 GPL-3.0 的授权范围内**。

所有者已拍板把它们留在仓库里。**这是取舍，不是法律结论**：它降低的是"直接再分发原版文件"
这一条，并不消除"用其 IP 做替代版本"这一条（见 §4.3）。

### 4.3 EA 的官方立场（写下来是为了别误判）

EA 的官方内容政策**不是沉默**，它有明文（原文见
[EA's content policy](https://help.ea.com/en/articles/security-and-rules/ea-content-policy/)）：

> **we don't allow game revivals, fan games, or community-run servers for them**
>
> … **obtain assets through "data mining"** …

EA 也明确要求使用其内容时带上这句声明，本项目照抄其原话：

> **This project is not endorsed by or affiliated with EA or its licensors.**
>
> 本项目未获 EA 或其许可方的认可或关联。

同时适用 EA 政策里的**非商业**要求：本项目不接受赞助、不卖周边、不把构建产物放在任何付费墙后面。

**没有任何"官方默认允许"这回事。** 2024-05，EA/PopCap 曾私下警告叫停粉丝重制项目
《PvZ 3: What Could Have Been》（开发者原话：*"It is not a cease and desist or a DMCA. It was a
warning."*），且该团队改名并改为自绘素材后仍被拒绝。完整调研与出处见
[`docs/报告/PvZ开源项目的许可证与版权素材处理调研.md`](docs/报告/PvZ开源项目的许可证与版权素材处理调研.md)。

### 4.4 开发用的参考实现

开发时用到的上游参考实现与拆包素材放在 `refer/`（见 `docs/00-架构总览与开发路线.md` §0.4），
该目录已被 `.gitignore` 排除，**从未进入过 git 历史**，因此不会被公开分发。

---

## 5. 构建期依赖

以下依赖通过 Maven（`mavenCentral` / `maven.fabricmc.net` / `libraries.minecraft.net`）拉取，
**不打进本仓库**，各自适用其原始许可；`shadowJar` 打出的 fat jar 会包含它们，分发该 jar 时同样
受其许可约束。

- **Fabric 系**：`sponge-mixin`、`tiny-remapper`、`mapping-io`、`class-tweaker`（Apache-2.0）
- **ASM** `org.ow2.asm:*`（BSD-3-Clause）、**SAT4J** `org.ow2.sat4j:*`（LGPL-2.1）
- **Mojang 库**：`com.mojang:datafixerupper`、`com.mojang:brigadier`（MIT 系，与 Minecraft
  游戏本体无关，是发布在 Maven 上的独立库）
- **LWJGL** `org.lwjgl:*`（BSD-3-Clause）
- **序列化/日志/工具**：Gson（Apache-2.0）、Guava（Apache-2.0）、fastutil（Apache-2.0）、
  SLF4J（MIT）、Logback（EPL-1.1 / LGPL-2.1）、Netty（Apache-2.0）

---

## 6. 修改记录

| 日期 | 改动 |
|---|---|
| 2026-09 | 首次建立本文件；仓库补 `LICENSE`（GPL-3.0）；补站酷快乐体的 OFL 副本 |
| 2026-09 | 1719 个原版素材移出仓库工作区（`tools/exclude_pvz_assets.py` → 本地 `local-assets/`，git 排除）；§4 重写为"不在仓库 + EA 政策对齐"版；新增 `docs/素材对照表.md` 记录逐条判决 |
