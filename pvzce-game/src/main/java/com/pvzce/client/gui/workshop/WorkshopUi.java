package com.pvzce.client.gui.workshop;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.*;
import java.util.function.Consumer;
final class WorkshopUi {
    private WorkshopUi() {}
    static String text(String key, String fallback) { return GuiLang.raw("gui.pvzce.workshop." + key, fallback); }
    static String label(String key) {
        return text("label."+key, LABELS.getOrDefault(key,key));
    }
    private static final java.util.Map<String,String> LABELS=java.util.Map.ofEntries(
        java.util.Map.entry("plants","植物"),java.util.Map.entry("zombies","僵尸"),java.util.Map.entry("levels","关卡"),
        java.util.Map.entry("tags","标签"),java.util.Map.entry("textures","贴图"),java.util.Map.entry("sounds","音频"),
        java.util.Map.entry("animations","动画 / 动作绑定"),java.util.Map.entry("lang","名称与翻译"),java.util.Map.entry("projectiles","弹丸"),
        java.util.Map.entry("particles","粒子特效"),java.util.Map.entry("resources","资源"),java.util.Map.entry("slots","卡槽"),
        java.util.Map.entry("tools","工具"),java.util.Map.entry("scene_elements","场景元素"),java.util.Map.entry("liquids","液体"),
        java.util.Map.entry("sound_events","音效事件"),java.util.Map.entry("level_themes","关卡主题"),java.util.Map.entry("level_categories","关卡分类"),
        java.util.Map.entry("endless_schedules","无尽波次表"),java.util.Map.entry("dialogue_characters","对话角色"),java.util.Map.entry("damage_types","伤害类型"),
        java.util.Map.entry("attributes","属性定义"),java.util.Map.entry("health","生命值"),java.util.Map.entry("animation_clips","动作与动画片段对应"),java.util.Map.entry("move_speed","移动速度"),
        java.util.Map.entry("bite_damage","啃咬伤害"),java.util.Map.entry("bite_interval","啃咬间隔（tick）"),java.util.Map.entry("can_swim","允许游泳"),
        java.util.Map.entry("capabilities","能力组合"),java.util.Map.entry("cost","消耗与冷却"),java.util.Map.entry("placement","放置规则"),
        java.util.Map.entry("texture","静态贴图"),java.util.Map.entry("animation","主动画"),java.util.Map.entry("animation_dir","动画目录"),
        java.util.Map.entry("render_scale","绘制倍率"),java.util.Map.entry("interval","间隔（tick）"),java.util.Map.entry("shots","发射配置"),
        java.util.Map.entry("projectile","弹丸"),java.util.Map.entry("damage","伤害"),java.util.Map.entry("count","数量"),
        java.util.Map.entry("every","生产间隔（tick）"),java.util.Map.entry("first_delay","首次延迟（tick）"),java.util.Map.entry("amount","产量"),
        java.util.Map.entry("resource","资源 ID"),java.util.Map.entry("frames","图片帧（按顺序播放）"),java.util.Map.entry("delay","每帧时长（秒）"),
        java.util.Map.entry("delays","逐帧时长（秒）"),java.util.Map.entry("loop","循环"),java.util.Map.entry("rate","播放速度"),
        java.util.Map.entry("on_end","播放结束动作"),java.util.Map.entry("values","标签成员"),java.util.Map.entry("replace","替换原标签"),
        java.util.Map.entry("pieces","护甲部件"),java.util.Map.entry("durability","耐久度"),java.util.Map.entry("radius","作用半径"),
        java.util.Map.entry("fuse_ticks","引信（tick）"),java.util.Map.entry("equipment","装备表现"),java.util.Map.entry("size","动画尺寸"),
        java.util.Map.entry("anchor","动画锚点"),java.util.Map.entry("order","显示顺序"),java.util.Map.entry("upgrade","升级前置")
    );
    static void ask(Screen owner, String title, String initial, Consumer<String> accept) {
        int width = Math.min(540, owner.client().guiWidth()-20), height = 180;
        int x = (owner.client().guiWidth()-width)/2, y = (owner.client().guiHeight()-height)/2;
        Dialog dialog = new Dialog(x,y,width,height,title).frameScale(0.2F).inlineTitle();
        EditBox box = new EditBox(x+16,y+70,width-32,30,32768,null);
        box.setValue(initial,false);
        dialog.addChild(box);
        dialog.addChild(new Button(x+16,y+20,(width-40)/2,30,text("ok","确定"),()-> { owner.removeDialog(dialog); accept.accept(box.value()); }));
        dialog.addChild(new Button(x+24+(width-40)/2,y+20,(width-40)/2,30,text("cancel","取消"),()->owner.removeDialog(dialog)));
        owner.showDialog(dialog);
    }
}
