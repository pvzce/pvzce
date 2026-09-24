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
