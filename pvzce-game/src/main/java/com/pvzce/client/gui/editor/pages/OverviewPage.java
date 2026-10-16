package com.pvzce.client.gui.editor.pages;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.gui.components.*;
import com.pvzce.client.gui.editor.*;
import java.util.*;

/** The first tab keeps optional editing and gameplay mechanisms out of the permanent toolbar. */
public final class OverviewPage implements EditorPage {
    private record Entry(String id,String label,boolean hasPage){}
    private int page;
    public String id(){return "overview";}
    public String label(){return "关卡";}
    public int order(){return 0;}
    public void build(EditorContext context){
        var area=context.fullContent();int x=area.x()+12,w=area.width()-24,top=area.y()+area.height();
        int bh=28;
        context.own(new Button(x,top-46,Math.min(210,w/2),bh,"添加机制 / 编辑功能…",()->picker(context)));
        List<Entry> options=new ArrayList<>();
        for(EditorPage item:context.screen().editorAllPages())if(EditorModules.optional(item.id())&&context.screen().editorModules().active(item)&&item.visibleFor(context))options.add(new Entry(item.id(),item.label(),true));
        for(var value:context.draft().getArray("mechanics")){
            if(!value.isJsonObject()||!value.getAsJsonObject().has("type"))continue;
            Identifier type=Identifier.tryParse(value.getAsJsonObject().get("type").getAsString());
            if(type==null||options.stream().anyMatch(item->item.id().equals(EditorModules.mechanicPage(type))))continue;
            options.add(new Entry(EditorModules.mechanicPage(type),EditorModules.mechanicLabel(type),false));
        }
        int count=Math.max(1,(area.height()-116)/48);page=Math.min(page,Math.max(0,(options.size()-1)/count));
        for(int i=page*count;i<Math.min(options.size(),(page+1)*count);i++){
            Entry item=options.get(i);int y=top-102-(i-page*count)*48;
            boolean shown=context.screen().editorVisiblePages().contains(item.id());
            var open=context.own(new Button(x,y,item.hasPage()?Math.max(80,w-142):w,32,item.label()+(item.hasPage()?(shown?" · 已显示":" · 已收起"):" · 已启用（无独立页签）"),()->context.screen().openEditorModule(item.id())));
            open.setActive(item.hasPage());
            if(item.hasPage())context.own(new Button(x+w-132,y,132,32,shown?"收起页签":"显示页签",()->context.screen().setEditorModuleShown(item.id(),!shown)));
        }
        if(options.size()>count){
            context.own(new Button(x,area.y()+8,60,26,"←",()->{page=Math.max(0,page-1);context.rebuildPage();}));
            context.own(new Button(x+68,area.y()+8,60,26,"→",()->{if((page+1)*count<options.size())page++;context.rebuildPage();}));
        }
    }
    private void picker(EditorContext context){
        var additions=context.screen().editorModules().additions(context,context.screen().editorAllPages());
        int w=Math.min(640,context.client().guiWidth()-24),h=Math.min(390,context.client().guiHeight()-24),x=(context.client().guiWidth()-w)/2,y=(context.client().guiHeight()-h)/2;
        Dialog dialog=new Dialog(x,y,w,h,"添加机制 / 编辑功能").frameScale(.2F).inlineTitle();
        var list=new AbstractSelectionList<EditorModules.Addition>(x+16,y+54,w-32,h-146,36,(client,item,rx,ry)->{
            client.fonts().body().draw(item.label(),rx+4,ry+8,.82F,1,1,.8F,1);
        });
        var functions=additions.stream().filter(item->item.mechanic()==null).toList();
        var mechanics=additions.stream().filter(item->item.mechanic()!=null).sorted(Comparator.comparing(EditorModules.Addition::key)).toList();
        list.setEntries(functions);dialog.addChild(list);
        dialog.addChild(new Button(x+16,y+h-78,(w-40)/2,26,"编辑功能",()->{list.setEntries(functions);list.select(functions.isEmpty()?-1:0);}));
        dialog.addChild(new Button(x+24+(w-40)/2,y+h-78,(w-40)/2,26,"玩法机制",()->{list.setEntries(mechanics);list.select(mechanics.isEmpty()?-1:0);}));
        dialog.addChild(new Button(x+16,y+16,(w-40)/2,28,"添加",()->{
            var selected=list.selected();if(selected==null)return;
            try{context.screen().addEditorModule(selected);context.screen().removeDialog(dialog);}catch(Exception e){context.setStatus(e.getMessage());}
        }));
        dialog.addChild(new Button(x+24+(w-40)/2,y+16,(w-40)/2,28,"取消",()->context.screen().removeDialog(dialog)));
        context.openDialog(dialog);
    }
    public void render(EditorContext context){
        var area=context.fullContent();
        context.client().fonts().body().draw("关卡功能与玩法机制",area.x()+12,area.y()+area.height()+4,.85F,1,1,.8F,1);
        if(area.height()>=190)context.client().fonts().body().draw("收起仅影响本次编辑入口，已有关卡数据仍保留。",area.x()+12,area.y()+44,.7F,.8F,.85F,.75F,1);
    }
}
