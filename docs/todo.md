# 待办（TODO）

> 这份文件记**已经知道、但这一轮没有做**的事。做完一条就删一条，不要在这里写"以后可能"。
> 现状与设计理由看 `当前项目架构.md` 与各分册；历史看 `架构变更记录.md`。

---

## 1. 关卡增益的贴图仍是借来的

两个内置增益的图标（`common/buff/BuiltInBuffs`）现在是**复用已有素材**的占位：

| 增益 | 现在画的 | 应该画成 |
|---|---|---|
| `pvzce:auto_collect`（自动拾取） | `pvzce:textures/resource/sun`（资源目录里那张阳光） | 一个"拾取"图标的正式贴图 |
| `pvzce:mushroom_range`（远距蘑菇） | `pvzce:textures/gui/cards/puff_shroom`（小喷菇的卡面图标） | 一个"射程"图标的正式贴图 |

要换的时候只改两个地方，别的一行都不用动：

1. `BuiltInBuffs` 里那个 `Identifier`（`BuffIcon.of(texture)`）；
2. 如果新贴图是**图集里的一块**而不是一张独立 PNG，改用
   `new LevelBuff.BuffIcon(texture, u0, v0, u1, v1)` —— `BuffIconRow` 已经会走
   `drawTextureRegion`，`SeedCardRenderer` 那条路需要补一次同样的分支。

贴图放哪：`assets/pvzce/textures/gui/buff/<名字>.png`（新目录），命名跟增益的 path 一致，
例如 `auto_collect.png` / `mushroom_range.png`。UI 图标在项目里都是
`textures/gui/...` 这一族（见 `textures/gui/hud/sun_bank.png`），增益图标属于同一层。

> 注意 `SeedCardRenderer` 会把图标按自身宽高比塞进卡片的图标窗，所以新贴图**方形**最省事。

## 1b. 奖励页上的增益卡用的是种子包的框

1-9 / 2-9 首通时，草坪上落下的与结算页相框里放的是**增益自己的图标 + 种子包的框**（`SeedCardRenderer` 的
`CardKind.BUFF` 只决定"不印价格"）。这不是占位，只是取舍：项目里没有第二套"奖励卡"边框，
而且这一页说的是"你拿到了新东西"，与种子包同一个语义。要给增益做一套自己的卡片边框的话，
`SeedCardRenderer` 已经有 `chrome(...)`（铲子卡就是那么换的），加一个 `BUFF_CARD_BACKGROUND` 即可。

## 2. 编辑器不认识 `buffs`

关卡编辑器的信息页/卡池页都没有"这一关的增益"这一项：

- **不会丢数据**：`InfoPage.buildLevelJson` 从载入的 JSON 出发，只覆盖自己拥有的字段，
  所以手写的 `"buffs"` / `"max_buff_slots"` **会被原样写回去**（与 `conveyor`、
  `placement_zone` 同一条现状）；
- **但也没有 UI 去编辑它们** —— 今天配增益只能手写关卡 JSON，或者用
  `LevelBuffPlan.mapCodec()` 认的两个字段写在文件里。

要补的话，最小的形状是在卡池页（`CardsPage`）下面加一行"关卡的增益"：一个多选列表
（列出 `BuiltInRegistries.LEVEL_BUFFS` 的全部 id + 一个 `pvzce:player_choice` 复选项）
加一个 `max_buff_slots` 数字框。`MechanicPages` 那种"按注册表生成一页"的做法可以直接抄。

## 2b. 没获得的增益靠挂锁表达，没有"为什么锁着"的说明

池子里未获得的增益画的是与固定卡同一个挂锁（`ChooseSeedsScreen.drawLockBadge`），点它只响一声。
玩家看不到"通关 1-9 就能拿到它"。要补的话最省事的是把它接到已有的提示框上
（`client/gui/hud/HintBox` + `LevelHints` 的 `on_card_refused` 那条路），文案里点名是哪一关发的
—— 但 `LevelRewards` 只记"谁发"，不记"发给谁"，所以要反查全部关卡定义（`BuiltInRegistries.LEVELS`），
或者干脆在 `PlayerProfile` 旁边存一份"这个东西是哪一关给的"。

## 3. 增益选择没有"每关一份"的记忆

自动启用名单是**每世界一份**（`PlayerProfile.autoBuffs`，见 §4.5 的增益一节），
这是在设计阶段定下的：玩家勾一次"自动拾取"，是想在所有关卡都自动拾取。
如果以后想要"某些关卡自动开、某些关卡自动关"，那要改成按关卡存
（`saves/<world>/buff_prefs.dat` 之类），`LevelBuffSelection.planForRun` 是唯一的入口。

## 4. 增益只在关卡开始前可选

`StartLevelC2S` 是唯一带增益的包，也就是"一局开始了就不能再改"。这是有意的
（增益是选卡的一部分），但如果以后要加"关卡中途开关增益"（调试命令、mod 覆盖、
奖励解锁），需要一个新的包与一处 `LevelServer.activeBuffs` 的写入口 ——
现在那个字段只在构造与 `restore` 时被写，`sporeRangeMultiplier` 每发射一次都重读，
所以"中途改了立刻生效"这半边已经成立。

## 5. 旧的烘焙字体图集还躺在资源里

`assets/pvzce/font/ui.png`（16384×7168 RGBA，18MB）与 `ui.json`（7131 个字形）现在**没有任何代码读**：
文字已经在运行时从 TTF 光栅化（见 `架构-客户端.md` 的字体一节），`tools/generate_font_atlas.py` 也只服务于这两个文件。
留着它们的唯一作用是当"旧观感的对照物"——em 标定就是拿它的 98px 字 / 18 单位行算出来的。真要清掉时是三个文件的删除
（`ui.png`、`ui.json`、那个生成脚本），failsafe 是 `FontRenderer` 已经在缺字体时抛 `Missing bundled font assets/pvzce/font/<file>.ttf`，
不会静默回落到图集；对照数字届时抄进常量注释即可（现在也抄了）。

## 6. 逐字号的字形缓存没有淘汰，只有"整页重来"

`FontFace` 的字形缓存以 `(码点, 设备像素尺寸)` 为键，页面满了（4 页）就 `recycle()` 全部丢掉重光栅。
一屏文字装不满一页，所以现状够用；但"同一个面以很多不同字号画很多不同的字"（编辑器里缩放预览、mod 自己传 scale）
会让缓存反复重建。要做的话是 LRU + 页内空洞回收，而不是加页数 —— 加页数只是把问题推后，显存却是实打实的。

## 7. 横幅用的四张原版贴图已经没人引用

`assets/pvzce/textures/gui/hud/announce/{ready,set,plant,final_wave}.png`：开场三拍与最终波现在是**文本**
（`BannerAnimation` 走 `assets/pvzce/lang` 的 `pvzce.announce.*`，见 `架构-客户端.md`），这四张图只剩"原版长什么样"的
参照价值。真要清掉时是四个文件的删除，没有代码引用；`zombies_won.png` 在同一个目录里但**仍在用**（失败画面），别一起删。

## 8. 变异的名字写了两份

服务端 `MutationText`（中央横幅用）与客户端 `MutationHud`（右端面板用）各有一张 `id → 中文` 的表，因为跨线
传的只有 id 本身、而两边的措辞可以不一样（服务端那份会带上抽到的数字）。现在两份内容一致，加第 19 条变异时
要记得写两处。

要合成一处有几条路，都不大：把名字随 `MutationStateS2C.Entry` 一起下发（多一个字符串字段，代价是每条变异每
40 秒的心跳都带中文），或者把变异名放进 `assets/pvzce/lang`（键是 `pvzce.mutation.<path>`，`GuiLang.name`
现成的）——**后者更合项目的 i18n 约定**，服务端那份只能保留（服务端不该依赖客户端的语言文件，而横幅是服务端
下发的文本）。真要动手时，客户端的表删掉、`MutationHud.mutationName` 改成 `GuiLang.lookup` 即可。

## 9. 变异的数值区间只有档位倍率，没有逐条调参

18 条变异里只有 6 条（`RateMutation` 那五条 + 危机/打地鼠/墓碑的间隔）真的用了难度档的倍率，其余 12 条是
"有/没有"或"一次性"的效果（世界末日、孟德尔、水草、爆炸、卡槽替换……）。也就是说 地狱 与 简单 在这些条目上
的差别只有"多久出现一次""同时存在几个"。如果以后要给单条变异配权重或强度曲线，`Mutation.weight()` 已经在接口
上（今天 18 条都是默认值 10），改法是给它加一个"档位 → 权重"的重载，而不是在 `MutationRegistry.roll` 里写
按档位的特判表。

## 10. 变异关卡没有专门的战报

四个变异关卡与普通关共用选卡页与结算页：选卡页显示的是泳池白天的静态预览（变异还没发生，所以这是对的），
失败时结算页显示"存活 N 波 · 击杀 M · 用时 T"（`GameStateS2C` 的三个字段）。但没有"本局经历了哪些变异"的
战报 —— 而这份数据现在是现成的：变异的 id 与抽到的数字已经在 `level.dat` 的 `Mutations` 块里（见
`架构-服务端.md` §4.10），缺的只是把它读出来画一页。

## 11. 关卡还不能"固定出某个变异"

`/mutation add <id>` 已经有了（调试与截图用），但关卡文件里没有"这一关固定带着某个变异开场"的字段。要做的话是
一条关卡字段 + `MutationManager.start` 里的一行，价值在于给手工设计的关卡加一个确定性的钩子（而不是靠掷骰）。

## 12. 图鉴不显示"这一关给这张卡"

未解锁的植物在图鉴里只有一句「未解锁」，玩家看不到"通关 1-9 就能拿到它"。
`LevelRewards` 只记"谁发"、不记"发给谁"，所以显示这一行要反查全部关卡定义
（`BuiltInRegistries.LEVELS` 的 `rewards`），或者在 `PlayerProfile` 旁边存一份反向表。
与第 2b 条（增益的挂锁没有说明）是同一件事的两半，真要做得一起做。

## 13. 图鉴不显示"类型 / 强壮度"这类原版档位

原版卡片背面有 `类型：攻击 - 远程`、`强壮度：中等` 这类档位，本项目没有对应字段：
`PlantDef` 没有类型分类，僵尸只有真实血量而没有"低/中/高"的档位。
现在图鉴列的是**本项目自己的数**（植物的阳光/冷却/血量、僵尸的血量/速度/咬伤、资源的价值），
要补原版那两行就得给内容加字段并逐条填五十多个值 —— 那是一次内容活，不是显示层的活。

## 14. 图鉴里的僵尸没有"未解锁"

这一版没有"见过哪只僵尸"的记录，所以僵尸页一次列出全部 26 只，包括还没在任何关卡里出现过的。
要做成原版那样"见过才进图鉴"，需要一个跨关卡、跨世界的见过记录
（`PlayerProfile` 旁边加一个 `SeenZombies` 集合，`ZombieEntity` 第一次生成时登记），
并且要决定它在沙盒世界里怎么表现。
