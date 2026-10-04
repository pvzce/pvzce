# AGENTS.md —— 在这个仓库里干活要先知道的

PVZCE 是一个**独立运行的桌面 PvZ 游戏**（不是 Minecraft 模组），用 fork 自 Fabric Loader 的加载器
加载第三方 mod。服务端 60tps 权威模拟，客户端只读镜像，两边**靠包交换**通信（单机也是内存包队列）。
代码的现状看 `docs/当前项目架构.md`；文档体系与"这轮该改哪几份"看 `docs/README.md`。

## 命令

```bash
export GRADLE_USER_HOME=$PWD/.gradle-user   # ~/.gradle 只读，不加会在 wrapper 解压阶段失败

./gradlew :pvzce-game:test                      # 全量单测（约 2 分钟，机器相关；:pvzce-api:test 另有几例）
./gradlew :pvzce-game:test --tests 'com.pvzce.server.VaseTest'    # 只跑一个类/包（秒级，优先）
./gradlew :pvzce-game:run -Ppvzce.gameDir=$PWD/.smoke/gd \
    -Ppvzce.smoke="pvzce.smokeLevel=pvzce:yard/adventure/1_1 pvzce.smokeFrames=400 \
    pvzce.captureFrame=200 pvzce.capturePath=$PWD/.smoke/<主题>/s.png"   # 无头冒烟/截图
```

- **冒烟输出必须落在仓库内**（`.smoke/<主题>/`）且用绝对路径：写到 `/tmp` 会被沙箱**静默**吞掉。
- 一次改动**最多 1 次**冒烟启动；要看多个时刻就在那一次里用 `captureEvery` 拍一整条胶片。
- 冒烟钩子的完整清单在 `docs/冒烟与截图指南.md`（唯一出处），不要在这里或别处再抄一份。

## 铁律

- `refer/` 是**只读参考**（上游实现与 MC 反编译源码），版权边界见 `docs/00-架构总览与开发路线.md` §0.4。
  可以读、可以照设计重写，**不要复制源码、不要改**。
- `pvzce-loader/` 是 Fabric Loader 的裁剪 fork，**不在常规改动范围内**。
- 分层靠包约定维持（`api`/`common`/`server`/`client` 在同一个 source set），例外只有三类，
  由 `LayerDependencyTest` **按文件名白名单**钉住；加一处反向 import 就会红。不要放宽它，先读
  `docs/当前项目架构.md` §1。
- 一个事实只有一个出处：数值进 `PvzceConstants`，内容 id → 美术只有 `EntityArt`（`EntityTextures` 是它上面的兼容转发），
  放置判定只有 `PlantPlacement`……完整清单在 `docs/架构-扩展点与约定.md` §8（改之前先 grep 有没有现成实现）。
- 不记用例数。"全绿"只有在用例本身确定的时候才是信号；偶发失败要么改成确定性断言、要么删掉。

## 开工前：确认门（硬性）

动手写代码前先给出 **3~5 行**并等答复：**我读到的事实**（素材/引用面盘点）、**打算怎么做**、**我不确定的地方**。
要确认的是隐含事实与取舍，不是复述用户的指令。规则与两个真实代价见 `docs/验证约定.md` §2~§4。
盘点的结果就是"我读到的事实"——动画清单、`refer/` 里有什么、是不是已有机制、谁引用了它。

尽可能要多询问几轮问题，确保你的把握很强。最后问用户有没有额外补充，如果有的话，你需要评判这个补充，需要继续问的时候就继续。

## 收尾：一轮任务做完要动什么

**先确认行为/画面通过，再写文档**（回退时那份记录要重写第二遍）。
然后按 `docs/README.md` §3 的顺序走，最短版本：

1. `docs/架构变更记录.md`：**行为/结构真的变了**就记一条（现象 → 定论），只记最终落地的做法。
2. `docs/todo.md`：这轮**没做完 / 明确不做**的逐条加进去；做完的条目删掉。
3. `docs/踩坑清单.md`：踩到**会再犯**的坑才记（一条 = 现象 → 判据 → 出处）。
4. as-built 变了才改 as-built：`docs/当前项目架构.md`、`docs/架构-*.md`、`docs/UI切换与导航架构.md`。
   它们**只写现状**，不写"曾经…"——历史归变更记录。
5. 用户拍板了新取舍 → `docs/决策记录.md`；文档增删 → `docs/README.md` 台账。
6. 冻结文档（`01`/`02`/`03`、设计方案、`报告/*`、`mod-guide/*`）**不随任务更新**。
7. `python3 tools/doc_check.py`：改了源码却没改文档时它会点名候选文档；再跑一次
   `./gradlew :pvzce-game:test`（它会顺带校验文档里的符号引用与规模统计块）。
8. 提交：一行中文说清"这一批做了什么"，不写 `fix bug` / `update`。收官报告（如果用户要）按
   `docs/验证约定.md` §12.4 的七段结构写，放进 `docs/报告/`。

**被用户指出问题、修好之后，同样要走一遍上面这几步**——修复本身也要反思并落进文档，
不要只在对话里说一句"已修复"。

## 地图

| 想知道 | 看 |
|---|---|
| 代码现在长什么样、往哪扩 | `docs/当前项目架构.md`（入口）与 `docs/架构-*.md`（分册） |
| 这轮验到什么程度、流程怎么走 | `docs/验证约定.md` |
| 冒烟/截图的参数 | `docs/冒烟与截图指南.md` |
| 为什么当初这么定 | `docs/决策记录.md`、`docs/架构变更记录/`（历史全文） |
| 还没做什么 | `docs/todo.md`（**唯一出处**） |
| 界面怎么切屏 | `docs/UI切换与导航架构.md` |
| mod 怎么写 | `docs/mod-guide/`（对外文档，只在用户点名时改） |
