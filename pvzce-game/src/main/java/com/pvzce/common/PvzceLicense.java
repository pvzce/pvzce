package com.pvzce.common;

import com.pvzce.launcher.PvzceVersions;

/**
 * GPL-3.0 要求的法律声明（"Appropriate Legal Notices"），一处定义、两处显示。
 *
 * <p>GPL-3.0 §5(d) 要求有交互界面的作品显示版权声明、无担保声明与许可取得方式，并且给的是
 * 图形界面的场景（"for a GUI interface, you would use an about box"）。所以这段文本有两个
 * 出口：启动日志（{@code PvzceGame.main} 打一行）与设置里的"关于"页（{@code AboutScreen}）。
 * 两处读同一份常量，改一次两处都跟着走——分开写会漂。
 *
 * <p>文本里的每条事实都有出处，改之前先核对：
 *
 * <ul>
 *   <li>版权行与许可版本 → 仓库根 {@code LICENSE}；</li>
 *   <li>Fabric Loader 的 Apache-2.0 与字体 OFL 的分段 → 根 {@code THIRD-PARTY.md}；</li>
 *   <li>素材不属于本项目许可范围、以及那句 EA 免责声明 → {@code THIRD-PARTY.md} §4
 *       （原文是 EA 内容政策里要求照抄的句型）。</li>
 * </ul>
 *
 * <p><b>这里不是授权声明</b>：它只陈述本项目的代码按 GPL-3.0 分发，以及哪些部分不适用。
 */
public final class PvzceLicense {
    /** 版权行，与 {@code LICENSE} 的分发声明一致。 */
    public static final String COPYRIGHT = "Copyright (C) 2026 crystalneko";

    /** 项目主页。许可原文与第三方声明都在这个仓库里。 */
    public static final String SOURCE_URL = "https://github.com/crystalneko/pvzce";

    /**
     * 启动时打的那一行。
     *
     * <p>正文（无担保、许可全文去哪取）在"关于"页里展开；启动日志只给一行——每次启动刷十几行
     * 法律文本，读日志的人会把它当噪声跳过，那比不写还糟。
     */
    public static final String STARTUP_NOTICE = String.format(
            "%s %s — %s — GPL-3.0, no warranty; see \"关于\" in settings or LICENSE in the repo",
            PvzceVersions.GAME_NAME, PvzceVersions.GAME_VERSION, COPYRIGHT);

    /** "关于"页显示的多行文本。 */
    public static final String ABOUT_TEXT = String.join("\n", new String[]{
            PvzceVersions.GAME_NAME + " " + PvzceVersions.GAME_VERSION,
            "",
            COPYRIGHT,
            "",
            "本程序的代码是自由软件：你可以按自由软件基金会发布的 GNU 通用公共许可证"
                    + "（第 3 版，或你选择的任何更新版本）的条款再分发和/或修改它。",
            "",
            "本程序的分发是希望它有用，但「不提供任何担保」，甚至不提供适销性或特定用途适用性的"
                    + "默示担保。详见 GNU 通用公共许可证。",
            "",
            "许可全文见仓库根目录的 LICENSE 文件：",
            SOURCE_URL + "/blob/master/LICENSE",
            "",
            "pvzce-loader/ 中源自 Fabric Loader 的部分按 Apache-2.0 分发（该目录下 LICENSE）；"
                    + "四份中文字体按 SIL OFL-1.1 分发。",
            "",
            "游戏内容素材不属于上述任何许可范围——"
                    + "This project is not endorsed by or affiliated with EA or its licensors.",
    });

    private PvzceLicense() {
    }
}
