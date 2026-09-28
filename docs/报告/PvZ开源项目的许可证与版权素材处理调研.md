# PvZ 民间改版 / 重制 / 工具类开源项目的许可证与版权素材处理调研

调研日期：2026-09-28。所有 star / LICENSE / 目录结构数据取自当日 GitHub API（`/repos/{owner}/{repo}`、
`/git/trees/{branch}?recursive=1`），经 `git trees` 响应**未被截断**（每个仓库都确认了 `truncated: false`）。
素材存在性判断 = 默认分支目录树里是否有成规模的来源自原版的图片 / 音频 / `.reanim` / `main.pak`。

> 说明：本报告是**调研**，不是法律意见。所引用的免责声明只代表各仓库作者自己的表述。

---

## 案例表

| # | 仓库 | star | LICENSE（`license.spdx_id`） | 带原版拆包素材？ | README / LICENSE 里的声明（原文摘录） |
|---|---|---|---|---|---|
| 1 | [`wszqkzqk/PvZ-Portable`](https://github.com/wszqkzqk/PvZ-Portable) | 759 | `LGPL-3.0` | **没有**（全库 752 blob，`.gitignore` 显式忽略 `main.pak` / `main.unpak`；唯一的 mp3/png 来自内嵌的 SDL-Mixer-X 测试数据） | README「⚠️ Notice」四条 + License 三条，是全样本里写得最完整的（英文）：<br>「This repository does **NOT** contain any copyrighted game assets (such as images, music, or fonts) owned by PopCap Games or Electronic Arts. Users must provide their own `main.pak` and `properties/` folder from a **legally purchased copy**…」<br>「The **original game IP (Plants vs. Zombies) belongs to PopCap/EA**. This license applies **only to the code implementation** in this repository.」<br>「Non-Commercial: This project is not affiliated with, authorized, or endorsed by PopCap Games or Electronic Arts.」<br>「The author (wszqkzqk) **NEVER reverse engineered** the program… code generated directly through reverse engineering will **not be accepted**.」<br>另有 `src/SexyAppFramework/LICENSE`（PopCap 自家框架许可）+ 致谢「PopCap Games: For creating the amazing game and releasing their framework to the public with a permissive license.」 |
| 2 | [`hsk-dream/PVZ-Godot-Dream`](https://github.com/hsk-dream/PVZ-Godot-Dream) | 336 | `NOASSERTION`（**自定义非商用许可** `Custom Non-Commercial License (v1.0)`） | **没有**（1780 blob；`.gitignore` 最后一行是 `assets`；`animation/`、`resources/` 里是 `.tres/.res` 动画定义，全库仅 5 个 png） | README 顶部与 LICENSE 都写了（中英各一份）：<br>「**考虑到版权问题，将原版相关资源文件删除。**」<br>LICENSE：「All original game content belongs to PopCap Games and Electronic Arts (EA). This project is not affiliated with or endorsed by them.」<br>README：「本项目采用自定义非商用许可协议，**禁止任何形式的商业用途**…❌ 禁止：商业公司或组织内部使用；将本项目作为产品或服务的一部分进行销售、收费分发或在线提供」 |
| 3 | [`Patoke/re-plants-vs-zombies`](https://github.com/Patoke/re-plants-vs-zombies) | 226 | `CC0-1.0` | **没有**（370 blob，只有源码；README 要求把编出来的 exe 拷进原版游戏根目录运行） | README「# DISCLAIMER」（英文）：<br>「This project does not condone piracy」<br>「This project does not include any IP from PopCap outside of their open source game engine, this will only output the executable for a decompiled, fan version of PvZ」<br>「To play the game using this project you need to have access to the original game files by [purchasing it](https://store.steampowered.com/app/3590/Plants_vs_Zombies_GOTY_Edition/)」 |
| 4 | [`wszqkzqk/pypvz`](https://github.com/wszqkzqk/pypvz) | 258 | **没有 LICENSE 文件**（API `license` 为 `null`；`LICENSE`/`LICENSE.md`/`COPYING` 全部 404） | **有**（`resources/graphics/**` 1808 png、`resources/sound/**` 44 ogg、`resources/music/**` 9 首） | README 第 5 行（中文，唯一一处声明）：<br>「**本项目为个人python语言学习的练习项目，仅供个人学习和研究使用，不得用于其他用途。如果这个游戏侵犯了版权，请联系我删除**」 |
| 5 | [`marblexu/PythonPlantsVsZombies`](https://github.com/marblexu/PythonPlantsVsZombies) | 3767 | **没有 LICENSE 文件**（同上，全部 404） | **有**（`resources/**` 752 png + 8 jpg） | README 第 3 行（英文，唯一一处声明）：<br>「`It's only for personal learning and noncommercial use. If this game infringes the copyright, please let me know.`」 |
| 6 | [`GeeeekExplorer/PVZ`](https://github.com/GeeeekExplorer/PVZ) | 367 | `GPL-3.0` | **有**（`images/` 47 个原版贴图 + 根目录 `Grazy Dave.mp3`；全库 103 blob、28 gif + 18 png + 1 mp3 + 1 jpg） | **什么都没写**：README（11 KB）是纯技术笔记（类设计、Graphics View 用法、Qt 资料），全文没有 copyright / 版权 / EA / PopCap / 免责 / 商用 任何字样；LICENSE 是标准 GPL-3.0 全文，无附加声明 |
| 7 | [`ruslan831/PlantsVsZombies-decompilation`](https://github.com/ruslan831/PlantsVsZombies-decompilation) | 114 | `MIT`（但 `LICENSE.txt` 是**未填写的模板**：`Copyright (c) [year] [fullname]`） | **有，而且是最多的一个**：`images/` 315、`ImageLib/` 313，全库 4619 png + 333 ogg + 292 `.reanim` + 188 jpg，外加 `SexyAppFramework/DebugVS2005/main.pak` 原版资源包 | README 只有 3 行（英文）：「this is a decompilation of the game plants vs zombies version 1.0.0.1051 this project used to be a project by user kopie …」。**没有免责声明、没有 EA/PopCap 字样**。反倒是给 AI agent 的工作区规则 `AGENTS.md` 写了：「本项目仅作为《植物大战僵尸》原版源码与反汇编实现参考。禁止在本项目内进行任何构建、编译或链接尝试」 |
| 8 | [`jiangnangame/PVZ2PAK`](https://github.com/jiangnangame/PVZ2PAK) | 155 | `NOASSERTION`（**自定义《长江开源协议》v3**，`LICENSE-ENGLISH.md`） | **有，且直接带了游戏本体**：根目录 `PlantsVsZombies.exe`、`SpareStarter.exe`、`bass.dll`；`images/` 300、`reanim/` 2497、`sounds/` 185，全库 2895 png + 168 ogg + 12 mp3 | README（英文）红字段落：<br>「We developed PVZ2PAK only for learning purposes. You must not use the program for commercial purposes, otherwise the legal issues should be resolved by yourself.」<br>「Please support [Genuine PVZ](https://www.ea.com/games/plants-vs-zombies) by Popcap Games.」<br>长江协议 §三.1「使用者不得将作品直接或间接应用于任何的盈利性用途」、§三.5「使用者不得将作品中的某一部分，单独肢解出来应用于其他项目」 |
| 9 | [`dkheng/PvZ-Unity`](https://github.com/dkheng/PvZ-Unity) | 186 | `MIT` | **有**：`Assets/` 1619 blob，454 png + 59 ogg + 3 mp3 + 2 wav + 60 `.anim` | README 第一段（中文）**只有一句**相关表述，且没有点名版权方：<br>「自定义了一些新植物、新僵尸、新关卡，**部分所用素材来源于网络**。」<br>`LICENSE` 是标准 MIT，`Copyright (c) 2023 dkheng`，无附加声明 |
| 10 | [`EFrostBlade/PVZHybrid_Editor`](https://github.com/EFrostBlade/PVZHybrid_Editor) | 247 | `MIT` | **有**：`res/cards/pvzhe_plants` 371 + `res/cards/pvzhe_zombies` 142 = 513 张卡面 png（杂交版游戏内卡面） | **仓库根目录没有 README.md**（`README.md` 404；只有 `3.19适配说明.md`、`启动性能修复说明.md`）。MIT `Copyright (c) 2024 EFrostBlade`，无任何版权 / 免责声明 |
| 11 | [`Teyliu/PVZF-Translation`](https://github.com/Teyliu/PVZF-Translation) | 1168 | `NOASSERTION`（`LICENSE` 正文是 **CC BY-NC 4.0**：`Attribution-NonCommercial 4.0 International`） | **有**（1166 png + 17 mp3 + 10 ttf + 4 otf + 2 ogg 的翻译 mod 构建产物） | README 顶部：「this repo's codes contain the **translation files and the associated mod artifacts**. Please reach out to us via e-mail or on Discord if you need the **Source code** for the **Translator mod**」（即：不发源码，只发产物） |
| 12 | [`Gzh0821/pvzge_web`](https://github.com/Gzh0821/pvzge_web) | 210 | `Apache-2.0` | **有，但走 Git LFS 外置**：`.gitattributes` 里 `docs/assets/resources/native/…zip filter=lfs`；仓库体积 3.6 GB | README：「Install Git LFS before cloning this repository. After pulling updates, run `git lfs pull` to download large game assets before serving `docs/`…」——**没有任何版权 / 免责声明** |

**为对照而查、未列入表内的相关事实**

- [`lmintlcx/pvztoolkit`](https://github.com/lmintlcx/pvztoolkit)（694★，`GPL-3.0`）与 [`lmintlcx/pvztools`](https://github.com/lmintlcx/pvztools)（156★，`GPL-3.0`）：修改器，**不带原版素材**（只有 `res/` 自带 UI 资源）。README 只写「PvZ Toolkit 的源代码采用 GPL-3.0 协议发布, 如若使用需要以相同的协议继续开源免费.」，**没有任何 EA/PopCap 版权或免责声明**。
- [`Zhuagenborn/Plants-vs.-Zombies-Online-Battle`](https://github.com/Zhuagenborn/Plants-vs.-Zombies-Online-Battle)（629★，`MIT`）：DLL 注入式对战 mod，仓库内只有 `game/userdata` 等配置与 docs（62 blob、3 png、1 jpg、1 gif），**不含美术素材**；README 有「## License / Distributed under the *MIT License*.」但没有版权免责声明。
- [`Mewnojs/PlantsVsZombies.NET`](https://github.com/Mewnojs/PlantsVsZombies.NET)（158★，`MIT`）：540 blob 里只有 2 png + 1 bmp，**不携带 WP 版素材**（README 让你把 `Content` 解压到 `Lawn_Android/Assets/Content`）。
- PopCap 自己的 `SexyAppFramework` 是**开源**的（[SourceForge 项目页](https://sourceforge.net/projects/popcapframework/)，License 标注为 "Other License"）。其许可原文（见 §EA 的态度）允许源码/二进制再分发，第 3 条禁止用 PopCap 名义背书、禁止产品名含 "PopCap"，第 2 条要求致谢句。同一页面下有一条 2025 年的用户评论指出该包**缺了 Todd Foley library（todlib）那部分文件**，"probably due to legal reasons"——说明连 PopCap 放出来的框架也不是完整的游戏代码。

---

## 模式归纳

### 模式 A ——「仓库完全不含素材，用户自备」（风险最低，且是**头部项目**的选择）

做法：代码只实现引擎/逻辑；运行时从**用户自己合法购买的那份游戏**里读 `main.pak` / `properties/`；
`.gitignore` 显式排除资源包；README 用大字写「本仓库不含任何 PopCap/EA 版权素材」。

采用者：`wszqkzqk/PvZ-Portable`（LGPL-3.0，`.gitignore` 里有 `main.pak` / `main.unpak`）、
`Patoke/re-plants-vs-zombies`（CC0-1.0，README 直说「要把 exe 拷进原版游戏根目录才能玩」）、
`hsk-dream/PVZ-Godot-Dream`（主动删素材 + 自定义非商用协议）。

**注意这三个是不同许可证（LGPL / CC0 / 自定义），说明"不带素材"与"选什么许可证"是两件独立的事。**

### 模式 B ——「仓库带素材 + 显式免责/非商用声明」

做法：素材照进，但在 README 里写清「仅供学习研究、禁止商用、版权归 PopCap/EA、请支持正版」。

采用者：`jiangnangame/PVZ2PAK`（连 `PlantsVsZombies.exe` 一起带，配《长江开源协议》+ 红字免责）、
`wszqkzqk/pypvz`（无 LICENSE，首页写「如果这个游戏侵犯了版权，请联系我删除」）、
`marblexu/PythonPlantsVsZombies`（无 LICENSE，写 "only for personal learning and noncommercial use"）。

### 模式 C ——「仓库带素材，但**声明等于没写**」

做法：有 LICENSE（甚至是 GPL/MIT 这种正规协议），但 LICENSE 只谈代码；README 完全不提版权方。

采用者：`GeeeekExplorer/PVZ`（GPL-3.0 + 47 张原版贴图 + 原版 BGM，README 11 KB 全文零声明）、
`EFrostBlade/PVZHybrid_Editor`（MIT + 513 张卡面，仓库干脆没有 README）、
`Gzh0821/pvzge_web`（Apache-2.0 + Git LFS 里的游戏资源，零声明）。

**这是样本里数量最多的一类——"用了 MIT/GPL 就等于处理完了版权"是普遍的误解。**

### 模式 D ——「素材外置：Git LFS / Releases / 构建产物」

做法：素材不进主仓库树，或用 Git LFS 指向外部存储，或只通过 Releases 分发。**注意：这只是搬运方式，
不等于降低了法律风险**——`pvzge_web` 仍然在 GitHub 的 LFS 里托管这些资源，克隆时照样会拉下来。

采用者：`Gzh0821/pvzge_web`（Git LFS）、`Teyliu/PVZF-Translation`（只往 Releases 发 mod 构建产物，源码另议）。

### 模式 E ——「说不清 / 自相矛盾」（最坏的一类，因为声明不可信）

- `ruslan831/PlantsVsZombies-decompilation`：MIT 协议文本里 `Copyright (c) [year] [fullname]` **占位符都没填**，
  同时仓库里躺着 `main.pak` + 4619 张 png；README 不提版权，反而有个 `AGENTS.md` 写「禁止构建本项目」。
- `dkheng/PvZ-Unity`：MIT + 454 张 png + 59 个 ogg，README 只承认「**部分所用素材来源于网络**」——
  既不写版权方，也不写非商用，还把 MIT 套在了别人素材的目录上。
- `wszqkzqk/pypvz` → `wszqkzqk/PvZ-Portable`：**同一个作者、同一个 IP，前后两版策略完全反转**
  （带 1808 张素材 + 一句话免责 → 零素材 + LGPL-3.0 + 四段式声明）。这是本调研里最有说服力的
  「作者自己后来怎么想」的证据。

---

## EA 的态度与真实案例

### 1. 成文政策：非商用二创可以，**"游戏复活 / 同人游戏 / 社区服务器"明确不行**

EA 官方《EA's content policy》逐条（原文，2026-09-28 抓取）：

> Our fans can use our game content, including gameplay and original characters, for personal uses or personal
> projects, including fan sites and videos, as long as they follow these principles:
> **Don't sell your content.** Don't use our game content for commercial purposes…
> **Be original. Don't use our games to create knock-off or spin-off games or products.** While we're grateful
> that our fans still love playing our older games, **we don't allow game revivals, fan games, or community-run
> servers for them.** …
> **Don't imply endorsement or affiliation.** … include the following statement: "**This [project/website] is not
> endorsed by or affiliated with EA or its licensors.**"
> **Mods.** **In general, we don't allow any modifications to our games.** However, we understand that modding
> is important to some of our communities and we have published mod guidelines for **certain games only**:
> Command & Conquer Policy / The Sims 4 Policy.

同页另一条与"拆包素材"直接相关：

> For example, don't show hacks or cheats, promote the sale of in-game currency, display offensive or inappropriate
> content, **obtain assets through "data mining"**, or distribute our games and content on file-sharing websites
> or torrents.

同页还专门提示**音乐是第三方授权的**，第三方可能不同意你在视频站使用：
> **Music.** Some of our games contain music we license from third parties, and those third parties may object to
> your use of their music on video sharing sites, resulting in potential copyright issues.

链接：<https://help.ea.com/en/articles/security-and-rules/ea-content-policy/>

**读法**：EA 的书面政策**没有**点名 PvZ；PvZ 也没有出现在它给过 mod 指南的两个游戏（C&C、The Sims 4）里。
但「fan games / game revivals 不允许」「data mining 取素材不允许」这两条对"重制 + 带拆包素材"的组合是**逐条命中**的。

### 2. 真实案例（编号即证据强度）

**(a) 2024-05 《PvZ 3: What Could Have Been》被 EA 直接叫停**——最硬的一个案例，有 EA 原话。

开发者公告（[X/Twitter @PvZ3_WCHB](https://x.com/PvZ3_WCHB/status/1794141014543995287) 与
[MP1st 报道](https://mp1st.com/news/fan-developed-plants-vs-zombies-3-game-using-the-ip-without-permission-gets-shutdown-by-ea)、
[DualShockers 报道](https://www.dualshockers.com/ea-shuts-down-plants-vs-zombies-3-fan-game/)，2024-05-24~27）：

> PopCap and EA have contacted us directly to stop development on Plants vs Zombies 3: What Could Have Been.
> **It is not a cease and desist or a DMCA. It was a warning**, and the team has decided they'd rather not take the risk.
> … We believe they managed this situation awfully and unprofessionally, however we don't have a way to fight back.

EA 发给该团队的原信（由 MP1st 全文转载）：

> We love to see expressions of creativity and dedication from our community of players, and we're **generally OK
> with fair and non-commercial uses of our IP in many cases**. But we have to put some limits in place, and one of
> these is **the explicit use of our IP to create new or alternate versions of our games**. … Unfortunately, the game
> you're developing is **an alternate version of our PvZ 3 game that uses PvZ characters, art, and story**. For that
> reason, we must ask you to stop development of your project.

团队后续补充（说明**"改名 + 自绘素材"也不管用**）：
> Just an FYI, the name (WCHB) wasn't the culprit. After arranging a rebrand, they desperately pulled the
> "Don't use any of our characters. Even if you make every asset on a unique style by your own pen".

要点：①不是 DMCA、不是 C&D，是**私下警告**——GitHub 上不会留下 DMCA 记录；②团队规模小、怕成本，
收到警告就自己停了；③**EA 在意的是"用它的 IP 做原作的替代版本"，而不是你有没有直接抄素材**。

**(b) 2025-07 中国 PvZ 改版圈集体停更**——规模最大，但**无官方实锤**。

[旅法师营地转载手谈姬《多个《植物大战僵尸》改版宣布停更》](https://www.iyingdi.com/tz/post/5615541)（2025-07-16/17）：

> 就在今天，《植物大战僵尸》圈发生了一次大地震。许多改版UP主宣布自己做的改版要停更或者延期了……
> 于是网友们开始找原因，找了一圈下来认为应该是版权方要求所致，不然很难解释所谓的"**不可抗力**"。
> …上线了杂交版的《植物大战僵尸》小程序版，也因为**版权方要求被下架**。…**截止到发文前，此事仍在发酵，官方没有给出任何解释。**

涉及的是《植物大战僵尸》杂交版（潜艇伟伟迷）、融合版（蓝飘飘 fly）等**国内知名改版**。
**务必注意：报道本身写明"目前这事没有实锤"，官方未置评。** 该文只说明了一件事：**存在一次让整个中文改版圈
同时收手的事件，且当事人把它称作"不可抗力"。**

**(c) 没有查到的部分（明确写"没查到"）**

- 没有查到任何一条**针对 GitHub 上 PvZ 仓库的 DMCA takedown 记录**（GitHub 的
  [DMCA 仓库](https://github.com/github/dmca) 里没有可直接检索到的 PvZ 案例；上面的案例表里 12 个仓库也都还在线）。
- 没有查到 EA/PopCap 就"**开源重制项目 / GitHub 仓库**"发表过的任何单独声明。
- 没有查到 `PvZ-Portable`、`re-plants-vs-zombies`、`PVZ2PAK`、`ruslan831/…-decompilation` 等被要求下架的任何公开记录。
- 关于 "Knockout City 私服 2025-11 被版权 takedown" 的说法，只在
  [Tech Insider 的 Stop Killing Games 汇总表](https://tech-insider.org/stop-killing-games-california-bill-2026/)里被动提到
  一行，另有 [Reddit 讨论](https://www.reddit.com/r/Games/comments/1bu6ri2/accursed_farms_the_largest_campaign_ever_to_stop/)称
  "EA 在私服发布前就已停止发行、与该私服无关"。**证据不足，不作为论据使用。**

---

## 风险排序与结论

**结论先给：不该带素材。** 理由不是"法律上必输"，而是**收益/代价不成比例**：

| 维度 | 仓库带原版素材 | 仓库只带代码 + 自研素材 |
|---|---|---|
| **触发概率** | 有真实触发记录：2024-05 EA 私下警告叫停 PvZ 同人作；2025-07 中文改版圈集体停更（无实锤但当事人称"不可抗力"） | **样本里 0 例**：`PvZ-Portable`（759★）、`Patoke`（226★）、`PVZ-Godot-Dream`（336★，还主动删了素材）都长期在线且活跃更新 |
| **后果形式** | EA 的首选手段是**私下警告**而不是 DMCA。也就是说：**你不会收到 GitHub 通知，但会被要求停更**；不遵守才会升级 | 最常见的后果是"没人管" |
| **升级路径** | 警告 → C&D → DMCA（GitHub 会直接 disable 仓库，历史与 issue 全部丢失）→ 账号风险 | 几乎不存在升级路径：**没有可投诉的版权物** |
| **辩护空间** | 素材是 100% 可识别的 EA 版权物，"合理使用"要打四个要素，且**拆包行为本身违反 EA 政策里的 "obtain assets through data mining"**；EA 2024 年的信里已明确"用我们的 IP 做原作的替代版本"就是红线 | 代码是自研的，**声明可信**；真被找上门也只需改名字/改美术，不必删库 |
| **社区后果** | 一旦被要求停更，**所有 star / issue / PR / fork 归零**，作者要承担"帮了倒忙"的名声 | 可以被 fork、被接续，中文/英文社区都能接手 |
| **对用户的成本** | 用户点开就能玩，但项目随时可能消失 | 用户要多做一步（"请自备正版 `main.pak`"），`PvZ-Portable` 证明**这一步完全拦不住人**——它有 759★，还提供了 WebAssembly 在线试玩 |
| **素材必要性** | 1795 贴图 / 141 MB 里，**真正不可替代的只有"原版美术识别度"**，而不是"能不能跑" | 先用占位素材把功能做完，美术可以后补（`PVZ-Godot-Dream` 就是这么干的：动画定义全在，图删了） |

**一句话**：带素材换来的是"用户省一步"，代价是"整个项目随时可能被一句话清掉"。
`wszqkzqk` 本人从 `pypvz`（带 1808 张素材、无 LICENSE）走到 `PvZ-Portable`（零素材、LGPL-3.0、四段式声明）
这件事，本身就是最有价值的参考答案——**他做完第一个之后，第二个不这么干了。**

另外必须说清的一点：**许可证解决不了素材问题。** 案例表里 `GeeeekExplorer/PVZ`（GPL-3.0）、
`EFrostBlade/PVZHybrid_Editor`（MIT）、`dkheng/PvZ-Unity`（MIT）、`Gzh0821/pvzge_web`（Apache-2.0）
都用了正规开源协议，但协议只覆盖**作者自己的代码**；把它套到别人的美术和音频上，不产生任何授权效果，
反而会让声明**看起来**可信而实际不可信（模式 C / E）。

---

## 对 PVZCE 的建议

> 现状核对（`THIRD-PARTY.md`、`LICENSE`、`.gitignore`、`git ls-files`，2026-09-28）：
> 仓库根目录**已经有** GPL-3.0 `LICENSE` 和 `THIRD-PARTY.md`，措辞与定位基本正确；
> `refer/` 已被 `.gitignore` 排除且 `git log -- refer` 为空（**从未进入历史**，这点做对了）；
> 但 `pvzce-game/src/main/resources/assets/pvzce/` 下的 **2077 个文件已被 git 跟踪**
> （实测：textures 1795 png / 135.6 MB，music 15 ogg / 20.1 MB，sfx 167 ogg / 5.3 MB，animations 91 json / 34.4 MB）。
> 所以下面第 1 条要动的不是「多加一段声明」，而是**已经进历史的 2077 个文件**。

### 1（最重要）把素材移出仓库 —— 这是唯一的"降维"操作

声明写得再好，也只是降低"主观恶意"的观感；**把 1795 张贴图、15 首原版音乐、167 个原版音效从
git 历史里拿掉，才是把风险从"存在"变成"不存在"**。具体做法（三选一，按推荐度排序）：

1. **最干净**：删素材 + 加一个 `tools/import-assets.*` 脚本，让用户指向自己那份 GOTY 安装目录，
   脚本负责解包/转换到 `assets/pvzce/{textures,sounds,animations}`；目录进 `.gitignore`。
   `PvZ-Portable`（`main.pak` 进 `.gitignore`）、`Patoke`（要求拷进原版游戏根目录）都是这个路子。
2. **保留可跑性**：仓库里只放**占位素材**（纯色方块 / 程序化生成的临时图）+ 一份素材清单
   （文件名 → 尺寸 → 用途），让 `./gradlew :pvzce-game:run` 在没有原版素材时也能进主菜单。
3. **如果实在要保留分发**：至少**不要留在 git 历史里**（`git filter-repo` 重写 + force push），
   改为发布时打进 Release 附件，并在 README 顶部写清「Release 里的素材不属于本项目授权范围」。

> 注意第 3 条只是"降低可见度"，**不改变法律性质**：`pvzge_web` 用 Git LFS 托管素材，克隆时照样全量落地，
> 它仍然是"仓库携带原版资源"。

### 2 把现有声明升级成"EA 政策对齐版"，并点名素材

`THIRD-PARTY.md` §4 已经写对了方向（"不属于本项目的许可范围"、"与 PopCap/EA 没有任何关联，未获其授权或背书"），
但可以补三处**EA 政策原文里点名要求的措辞**，让声明更"对得上条款"：

- 直接使用 EA 要求的那句原话（英文照抄，中文附译）：
  > "This project is not endorsed by or affiliated with EA or its licensors."
- 明确写"**非商业**"：不加赞助按钮、不卖周边、不把构建产物放到任何付费/打赏墙后面
  （EA 政策：`Don't sell your content. … Don't use our game content for commercial purposes.`
  且明确把 Patreon 付费墙列为禁止项；YouTube/Twitch 广告与站点被动横幅广告是**被允许的例外**）。
- 把「素材版权归 PopCap/EA」**下沉到素材目录本身**：在 `assets/pvzce/` 下放一个 `README.md`（或 `ASSETS.md`），
  写清哪些文件是第三方版权物、来源、以及"再分发需自行取得授权"。这样即使有人只 clone 了一个子目录、
  或从 Release 里只抽走素材包，声明也跟着走。

### 3 注意：**EA 明确不允许 "game revivals, fan games, community-run servers"**，所以这几个方向要主动避开

- 不要做成"PvZ 的替代版本"的定位（EA 2024 年那封信的原话：`the explicit use of our IP to create new or
  alternate versions of our games`）。README 第一句建议写成"一个用 Java 写的中立塔防引擎 + 一个 PvZ 风格示例"
  或"PvZCE 是一个独立桌面塔防项目的技术演示"，而不是"植物大战僵尸的桌面重制版"。
- **不要运营联网服务**：EA 政策把 `community-run servers` 与 `fan games`、`game revivals` 并列禁止。
  好在 PVZCE 是"单机也是内存包队列"的架构，对外叙述上强调"本地联机 / 无官方服务器"比强调"多人服务器"安全得多。
- 不要把 PVZCE 做成"可注入原版游戏进程"的形态（那会落进 `Mods. In general, we don't allow any
  modifications to our games.`）——保持"独立运行、不碰原版可执行文件"是当前的正确姿态，别改。

### 4 如果作者坚持用 GPL-3.0 给自己的代码 + loader 是 Apache-2.0 fork：**方向是对的**（已核实）

**兼容性方向（已核实，结论无歧义）**：Apache-2.0 → GPL-3.0 **单向可用**；反向不行。

- **FSF（GNU 官方许可证列表）**，Apache License 2.0 条目原文：
  > "This is a free software license, compatible with version 3 of the GNU GPL. Please note that this license is
  > **not compatible with GPL version 2**, because it has some requirements that are not in that GPL version.
  > These include certain patent termination and indemnification provisions."
  <https://www.gnu.org/licenses/license-list.html#apache2>
- **Apache 基金会官方兼容性说明**：
  > "Despite our best efforts, the FSF has never considered the Apache License to be compatible with GPL version 2,
  > citing the patent termination and indemnification provisions… **Apache 2.0 software can be included in a GPLv3
  > project**."
  <https://www.apache.org/licenses/GPL-compatibility.html>

**正确写法（PVZCE 现状 + 需要补的地方）**：

| 项 | 现状 | 该怎么做 |
|---|---|---|
| 仓库根 `LICENSE` | ✅ 已有 GPL-3.0 全文（674 行） | 在 `LICENSE` 之后（或 `README` 顶部）加一行**版权行**：`Copyright (C) 2026 <作者名/ID>`。GPL 附录 "How to Apply These Terms to Your New Programs" 要求每个源文件带版权行 + 免责声明，仓库级至少要有这一行 |
| `pvzce-loader/LICENSE` | ✅ 已有 Apache-2.0 全文（201 行） | 保持不动 |
| 上游文件头 | ✅ `pvzce-loader/HEADER` 保留了 `Copyright 2016 FabricMC` + Apache-2.0 头 | 保持不动。**Apache-2.0 §4(c)** 要求：以源码形式再分发衍生作品时，必须保留原作品源码中**所有版权、专利、商标与归属声明**；**§4(b)** 要求：**修改过的文件必须带显著通知说明你改过**。`THIRD-PARTY.md` §2 已逐文件写清"139+20 个文件逐字节一致、2 个 Messages.properties 各追加 2 行、1 个 services 文件为新增"——这个记录**正好覆盖 §4(b) 的通知义务**，不过 §4(b) 字面要求的是"通知跟着文件走"，所以建议再在 loader 目录放一份它自己的 `CHANGELOG.md`，别只存在于根目录文档 |
| `NOTICE` 传播（§4(d)） | ✅ 已核实：**上游 Fabric Loader 没有 `NOTICE` 文件**（`NOTICE` 探测返回 404，`HEADER` 返回 200） | `THIRD-PARTY.md` 里"因此 §4(d) 的 NOTICE 传播义务不适用"这句话是**正确的**，可以保留。但建议顺手在 `THIRD-PARTY.md` 里记下这句判断的**核实方式与日期**，将来上游加了 NOTICE 时能被发现 |
| **模块级许可边界** | ⚠️ 目前只在根 `THIRD-PARTY.md` 里说明"`pvzce-loader/` 中源自 Fabric Loader 的部分 = Apache-2.0，`com/pvzce/launcher/` 新增部分 = GPL-3.0" | 建议在 `pvzce-loader/` **目录内直接放一份 `README.md`**，把这条边界写进去。理由：Apache-2.0 的归属声明要"跟着代码走"；有人单独 clone/copy 这个子目录时，根目录的 `THIRD-PARTY.md` 不会跟过去 |
| **生成的 fat jar / 发布产物** | ⚠️ | fat jar 里会**同时**包含 Apache-2.0 的 Fabric Loader 代码与 GPL-3.0 的 PVZCE 代码。整包按 GPL-3.0 分发没问题（兼容方向成立），但要在 Release 说明或 `THIRD-PARTY.md` 里写明"本 jar 内含 Apache-2.0 授权的 Fabric Loader 代码，其许可原文见 `pvzce-loader/LICENSE`"。**Apache-2.0 §4(a)** 要求向任何接收者提供一份本许可，**§4(c)** 要求保留归属声明——源码与二进制两种形式都适用，所以打出来的 jar 也要能追溯到 `pvzce-loader/LICENSE` |
| **新增文件的文件头** | 待确认 | 建议 `com/pvzce/launcher/*.java` 等**自研**文件统一加：`Copyright (C) 2026 <作者> / SPDX-License-Identifier: GPL-3.0-or-later`。不要给上游文件加 GPL 头（那会造成"重新许可"的误导），也不要改上游头的 `Copyright 2016 FabricMC` |
| GPL-3.0 版本号 | 待明确 | 现在 `THIRD-PARTY.md` 只写 "GPL-3.0"。建议统一写 **`GPL-3.0-or-later`**（或明确写 `GPL-3.0-only`）。这个区别会影响下游能不能升到 GPL-4.0。`PvZ-Portable` 用的是 `LGPL-3.0-or-later`，可以参考其写法 |

**一句话总结这条**：`GPL-3.0（我的代码）+ Apache-2.0（loader fork，原样保留）` 是**可以**的，
做法就是"根 GPL + 子目录 Apache + 逐文件保留原头 + 改过的文件标注已改 + 上游没 NOTICE 所以不用传播 NOTICE"，
方向和 `THIRD-PARTY.md` 现在写的完全一致；要补的是**版权行、模块内 README、构建产物里的归属说明**这三件小事。

### 5 给 README 加一段"用户怎么合法地跑起来"

`PvZ-Portable` 那句"Users must provide their own `main.pak` and `properties/` folder from a **legally purchased
copy**"加上 EA 官方购买链接（<https://www.ea.com/games/plants-vs-zombies/plants-vs-zombies> /
<https://store.steampowered.com/app/3590/>），是"降低主观恶意"成本最低的一步：
它把项目定位从"提供素材"变成"提供引擎，素材是你自己的"。如果按建议 1 做了素材外置，
这段就是必需品而不是可选项——**顺便也顺手把合规性变成了产品文档的一部分**。

### 6 如果作者要发到 GitHub 之外的渠道（B 站 / 贴吧 / itch.io），复核这三件事

- **不要用官方 logo / 官方宣传图做封面**（EA 政策：`Don't merge our brand with yours. Our company, studio and
  game logos should not be combined with your own branding.` 连社交账号头像和 banner 都点名了）。
- **BGM 单独小心**：EA 政策专门提示游戏音乐往往是第三方授权，第三方可能在视频站提版权主张。
  PVZCE 那 15 首原版音乐如果出现在演示视频里，视频本身可能被 B 站/YouTube 的 Content ID 打到——
  这与代码许可无关，是另一个风险面。
- **不要挂收款码 / 爱发电 / Patreon**：EA 政策把"付费墙"列为禁止项。如果确实需要支持，
  只接受**不附带任何回报**的捐赠，并且在 README 里明确写清"捐赠不换取任何授权或内容"。

---

## 附：本报告的全部一手查询入口

- GitHub REST API：`https://api.github.com/repos/<owner>/<repo>`（取 `stargazers_count`、`license.spdx_id`、
  `default_branch`、`size`）与 `https://api.github.com/repos/<owner>/<repo>/git/trees/<branch>?recursive=1`
  （取目录结构；对表中 12 个仓库逐次确认了响应 `truncated: false`）。
- raw 文件：`https://raw.githubusercontent.com/<owner>/<repo>/<branch>/{README.md,LICENSE,...}`
  （本次对每个仓库都探测了 `README.md / README.en.md / LICENSE / LICENSE.txt / LICENSE.md / COPYING / NOTICE / THIRD-PARTY.md`）。
- EA 官方政策：<https://help.ea.com/en/articles/security-and-rules/ea-content-policy/>
- FSF 许可证列表（Apache-2.0 条目）：<https://www.gnu.org/licenses/license-list.html#apache2>
- ASF 兼容性说明：<https://www.apache.org/licenses/GPL-compatibility.html>
- EA 叫停 PvZ3 同人作：<https://x.com/PvZ3_WCHB/status/1794141014543995287>、
  <https://mp1st.com/news/fan-developed-plants-vs-zombies-3-game-using-the-ip-without-permission-gets-shutdown-by-ea>、
  <https://www.dualshockers.com/ea-shuts-down-plants-vs-zombies-3-fan-game/>
- 2025-07 中文改版停更：<https://www.iyingdi.com/tz/post/5615541>
- PopCap Games Framework（SexyAppFramework）开源页：<https://sourceforge.net/projects/popcapframework/>
