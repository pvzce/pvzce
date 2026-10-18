package com.pvzce.client.gui.workshop;

import com.google.gson.*;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.components.*;
import java.util.*;

/** Content-oriented forms, ability cards and frame strips on the lossless editor's draft. */
final class WorkshopEditorView {
    private final WorkshopJsonScreen owner;
    private final WorkshopPresentation art;
    private final List<Runnable> labels = new ArrayList<>();
    private boolean rootPreview;
    private int previewWidth;
    private int page;
    private String context="";
    private final Map<String,Integer> pages=new HashMap<>();
    private String clip="";
    private String animationSource="";
    private com.pvzce.client.animation.AnimationPlayback playback;
    private com.pvzce.client.animation.ArtTarget target;
    private String group = "基本";
    private static final Set<String> ART = Set.of("texture","icon","animation","animations","animation_clips","animation_dir","render_scale","art","portrait_dir","portrait_insets","box_left","box_right","scale","size","anchor");
    private static final Set<String> SOUND = Set.of("sounds","sound","events","voice");

    WorkshopEditorView(WorkshopJsonScreen owner, WorkshopPresentation art) { this.owner=owner;this.art=art; }
    private PvzceClient client(){return owner.client();}
    private Button button(int x,int y,int w,int h,String text,Runnable action){var button=new Button(x,y,w,h,text,action).style(Button.Style.SEED_CHOOSER);owner.visualWidget(button);return button;}
    private void label(String text,int x,int y,int w){labels.add(()->WorkshopUi.fit(client(),text,x,y,w,.73F,.85F,.92F,.77F));}
    private void rebuild(){owner.onResize();}
    boolean build(JsonElement node, boolean root) {
        String nextContext=owner.locationKey()+"|"+group;
        if(!context.equals(nextContext)){pages.put(context,page);page=pages.getOrDefault(nextContext,0);context=nextContext;}
        labels.clear();rootPreview=false;
        if(root && owner.file().contains("/lang/"))return false;
        if(root && owner.file().contains("/animations/"))return animation(node.getAsJsonObject());
        int width=client().guiWidth(),height=client().guiHeight();
        int top=height-100;
        if(root) {
            java.util.List<String> groups=new ArrayList<>();groups.add("基本");
            if(owner.file().matches(".*/(plants|zombies|tools|slots)/.*")||node.getAsJsonObject().has("cost"))groups.add("消耗与放置");
            if(owner.file().matches(".*/(plants|zombies|projectiles)/.*"))groups.add("能力");
            groups.add("外观与动画");
            if(owner.file().matches(".*/(plants|zombies|projectiles|tools|sound_events)/.*"))groups.add("声音");
            if(!groups.contains(group))group="基本";
            int tabWidth=(width-24)/groups.size();
            for(int i=0;i<groups.size();i++){
                String next=groups.get(i);owner.visualWidget(WorkshopUi.tab(12+i*tabWidth,top,tabWidth-4,27,next,group.equals(next),()->{group=next;rebuild();}));
            }
            top-=18;
            List<String> keys=new ArrayList<>();
            for(String key:node.getAsJsonObject().keySet()) {
                if(key.equals("id"))continue;
                String section=key.equals("capabilities")?"能力":ART.contains(key)?"外观与动画":SOUND.contains(key)?"声音":Set.of("cost","placement","upgrade").contains(key)?"消耗与放置":"基本";
                if(section.equals(group))keys.add(key);
            }
            if(group.equals("能力")) {
                JsonArray caps=node.getAsJsonObject().has("capabilities")?node.getAsJsonObject().getAsJsonArray("capabilities"):new JsonArray();
                if(height>=330)label("点击上方「添加」组合能力；点击卡片配置参数。",20,top-8,width-40);
                array(caps,height<330?top+14:top-22);return true;
            }
            rootPreview=height>=330&&width>=600;previewWidth=Math.max(100,Math.min(240,width/3));
            if(owner.file().contains("/dialogue_characters/"))button(22,112,Math.max(80,previewWidth-20),27,"预览左右对话框",owner::previewCharacter);
            fields(node,keys,rootPreview?previewWidth+30:16,72,rootPreview?width-previewWidth-44:width-32,top-10);
            return true;
        }
        if(node.isJsonArray()){array(node.getAsJsonArray(),top);return true;}
        if(node.isJsonObject()){fields(node,new ArrayList<>(node.getAsJsonObject().keySet()),16,72,width-32,top+22);return true;}
        return false;
    }

    private void fields(JsonElement node,List<String> keys,int x,int bottom,int width,int top) {
        top=Math.max(top,bottom+58);
        int columns=width>500?2:1, rows=Math.max(1,(top-bottom)/58), count=rows*columns;
        page=Math.min(page,Math.max(0,(keys.size()-1)/count));
        int cellWidth=(width-(columns-1)*12)/columns;
        for(int i=page*count;i<Math.min(keys.size(),(page+1)*count);i++) {
            String key=keys.get(i);JsonElement value=node.getAsJsonObject().get(key);
            int slot=i-page*count,fx=x+(slot/rows)*(cellWidth+12),y=top-58-(slot%rows)*58;
            String title=key.equals("type")?"能力类型":WorkshopUi.label(key);
            if(key.contains(":"))title=art.item(key,"resources").name();
            label(title,fx,y+33,cellWidth-28);
            if(key.equals("id")||key.equals("type")) {
                label(value.getAsString(),fx,y+10,cellWidth);
                continue;
            }
            if(value.isJsonPrimitive()&&!value.getAsJsonPrimitive().isBoolean()&&!owner.isReference(key)&&!key.equals("kind")) {
                EditBox input=new EditBox(fx,y,cellWidth-28,27,32768,null).style(EditBox.Style.PARCHMENT);
                input.setValue(owner.inputText(node,key,value),false);
                input.setValueChangedListener(()->owner.setText(node,key,value,input.value()));
                owner.visualWidget(input);
            } else {
                String caption=value.isJsonObject()?"配置 "+title:value.isJsonArray()?"编辑（"+value.getAsJsonArray().size()+" 项）":value.isJsonPrimitive()&&value.getAsJsonPrimitive().isBoolean()?(value.getAsBoolean()?"✓ 已开启":"○ 已关闭"):owner.referenceName(key,value);
                button(fx,y,cellWidth-28,27,caption,()->owner.editValue(node,key,value));
            }
            button(fx+cellWidth-25,y,25,27,"×",()->owner.removeField(node.getAsJsonObject(),key));
        }
        if(keys.isEmpty())label("此分组尚未设置属性，可点上方「添加」选择。",x,top-45,width);
        paging(keys.size(),count);
    }
    private void paging(int total,int count){
        if(total<=count)return;
        var previous=new Button(12,32,34,26,"←",()->{page--;rebuild();}).style(Button.Style.SEED_CHOOSER);previous.setActive(page>0);owner.visualWidget(previous);
        label((page+1)+" / "+Math.max(1,(total+count-1)/count),52,40,48);
        var next=new Button(104,32,34,26,"→",()->{page++;rebuild();}).style(Button.Style.SEED_CHOOSER);next.setActive((page+1)*count<total);owner.visualWidget(next);
    }
    private void array(JsonArray array,int top) {
        int width=client().guiWidth();boolean compact=client().guiHeight()<330;boolean frames=owner.currentField().equals("frames");
        int columns=compact?1:frames?Math.max(1,(width-32)/145):Math.max(1,(width-32)/280);
        int rows=Math.max(1,(top-90)/(compact?72:frames?156:112));int count=columns*rows;
        int cw=(width-32-(columns-1)*10)/columns,ch=compact?62:frames?146:102;
        page=Math.min(page,Math.max(0,(array.size()-1)/count));
        for(int i=page*count;i<Math.min(array.size(),(page+1)*count);i++) {
            int index=i,slot=i-page*count,x=16+(slot%columns)*(cw+10),y=top-ch-(slot/columns)*(ch+10);
            JsonElement value=array.get(i);
            if(frames&&compact&&value.isJsonPrimitive())owner.visualWidget(new Button(x,y+30,cw-100,32,value.getAsString(),()->owner.editValue(array,Integer.toString(index),value)){
                public void render(PvzceClient client){
                    com.pvzce.client.gui.layout.MenuPageCanvas.surface(client,this.x,this.y,this.width,this.height,hovered);
                    art.draw(art.item(value.getAsString(),"textures"),this.x+3,this.y+2,28,28);
                    WorkshopUi.fit(client,value.getAsString(),this.x+36,this.y+11,this.width-40,.65F,1,1,.8F);
                }
            });
            else if(frames && value.isJsonPrimitive())owner.visualWidget(art.card(art.item(value.getAsString(),"textures"),x,y+30,cw,ch-30,()->owner.editValue(array,Integer.toString(index),value)));
            else {
                owner.visualWidget(new Button(x,y+38,cw,ch-38,summary(value),()->owner.editValue(array,Integer.toString(index),value)){
                    public void render(PvzceClient client){
                        com.pvzce.client.gui.layout.MenuPageCanvas.surface(client,this.x,this.y,this.width,this.height,hovered);
                        String[] parts=summary(value).split(" · ",2);
                        WorkshopUi.fit(client,"#"+(index+1)+"  "+parts[0],this.x+12,this.y+this.height-23,this.width-24,.95F,1,1,.8F);
                        if(parts.length>1&&this.height>=44)WorkshopUi.fit(client,parts[1],this.x+12,this.y+12,this.width-24,.72F,.7F,.85F,.75F);
                    }
                });
            }
            int bw=(cw-6)/3;
            button(x,y,bw,25,"前移",()->owner.moveEntry(array,index,-1)).setActive(index>0);
            button(x+bw+3,y,bw,25,"后移",()->owner.moveEntry(array,index,1)).setActive(index+1<array.size());
            button(x+(bw+3)*2,y,bw,25,"删除",()->owner.removeEntry(array,index));
            if(frames)button(compact?x+cw-96:x,y+30,compact?96:cw,compact?28:22,"#"+(index+1)+" · "+owner.frameDelay(array,index),()->owner.editFrameDelay(array,index));
        }
        if(array.isEmpty())label(frames?"还没有图片帧：点「添加」从贴图库选择。":"列表为空：点「添加」创建条目。",20,top-48,width-40);
        paging(array.size(),count);
    }
    private boolean animation(JsonObject json) {
        int w=client().guiWidth(),h=client().guiHeight();
        previewWidth=Math.max(110,Math.min(260,w/3));
        JsonObject clips=json.has("animations")&&json.get("animations").isJsonObject()?json.getAsJsonObject("animations"):new JsonObject();
        if(!clips.has(clip))clip=clips.keySet().stream().findFirst().orElse("");
        int x=previewWidth+30;
        button(x,h-104,Math.max(80,w-x-130),28,"动作："+(clip.isEmpty()?"暂无":clip),()->client().openScreen(new WorkshopListScreen(client(),"选择动画动作",clips.keySet(),name->{clip=name;rebuild();})));
        button(w-116,h-104,100,28,"新增动作",()->WorkshopUi.ask(owner,"动作名称","",name->{
            if(name.isBlank()||clips.has(name))return;
            JsonObject value=new JsonObject();value.add("frames",new JsonArray());value.addProperty("delay",.1);value.addProperty("loop",true);
            clips.add(name,value);json.add("animations",clips);clip=name;rebuild();
        }));
        if(clips.has(clip)&&clips.get(clip).isJsonObject()) {
            JsonObject current=clips.getAsJsonObject(clip);
            fields(current,new ArrayList<>(current.keySet()),x,h>=330?110:72,w-x-16,h-125);
            if(h>=330)button(x,70,Math.max(70,(w-x-24)/2),27,current.has("frames")?"图片帧排序":"动作参数",()->owner.enterClip(clip));
            if(h>=330)button(x+(w-x-24)/2+8,70,Math.max(70,(w-x-24)/2),27,"播放 / 暂停",()->{if(playback!=null)playback.setPaused(!playback.paused());});
        }
        if(h>=330)label("尺寸与锚点在高级属性中调整",x,104,w-x-16);
        return true;
    }
    private void animationPreview() {
        if(!owner.file().contains("/animations/")||!owner.atRoot()||client().animations()==null)return;
        art.activate();
        try {
            String source=owner.draft().toString()+"|"+clip;
            if(!source.equals(animationSource)) {
                var id=WorkshopPreviewScreen.assetId(owner.file());
                var file=com.pvzce.client.animation.AnimationResourceLoader.parse(owner.draft(),id);
                var selected=file.clip(clip).orElse(null);
                target=new com.pvzce.client.animation.ArtTarget(id);
                playback=selected instanceof com.pvzce.client.animation.FlipbookClip frame && file instanceof com.pvzce.client.animation.FlipbookFile flipbook
                    ? new com.pvzce.client.animation.FlipbookPlayback(client().animations(),target,flipbook,frame,clip,clip,null,client().animations().now())
                    : selected instanceof com.pvzce.client.animation.ControllerClip controller && file instanceof com.pvzce.client.animation.ControllerFile skeleton
                    ? new com.pvzce.client.animation.ControllerPlayback(client().animations(),target,skeleton,controller,clip,clip,null,client().animations().now()):null;
                animationSource=source;
            }
            if(playback!=null) {
                int side=Math.max(40,Math.min(previewWidth-12,client().guiHeight()-180));
                client().clipping().push(16,105,previewWidth-8,Math.max(20,client().guiHeight()-195));
                try {
                    client().beginOverlayWorldView(16,115,side,side,-1.2F,1.2F,-.25F,2.15F);
                    playback.render(client(),0,0,0,1,1);
                } finally {client().clipping().pop();client().beginGuiView();}
            }
        } catch(Exception e){WorkshopUi.fit(client(),"动画暂不可播放",22,100,previewWidth-20,.7F,1,.7F,.5F);}
    }
    boolean addToGroup(JsonObject root){
        if(!group.equals("能力")||owner.file().contains("/animations/"))return false;
        if(!root.has("capabilities"))root.add("capabilities",new JsonArray());
        owner.enter("capabilities");owner.addValue();return true;
    }
    static String summary(JsonElement value) {
        if(!value.isJsonObject())return value.isJsonPrimitive()?value.getAsString():"编辑条目";
        JsonObject object=value.getAsJsonObject();
        String type=object.has("type")?object.get("type").getAsString():object.has("projectile")?object.get("projectile").getAsString():"配置条目";
        String label=WorkshopUi.label(type.substring(type.indexOf(':')+1));
        var id=com.pvzce.api.util.Identifier.tryParse(type);
        if(id!=null)for(String category:List.of("plant_capability","zombie_capability","projectile_capability")){
            String name=com.pvzce.client.gui.GuiLang.lookup(category,id);if(name!=null){label=name;break;}
        }
        for(String key:List.of("interval","every","damage","amount","count","speed"))if(object.has(key)&&object.get(key).isJsonPrimitive())label+=" · "+WorkshopUi.label(key)+" "+object.get(key).getAsString();
        return label;
    }
    void render() {
        animationPreview();
        if(rootPreview){
            int h=client().guiHeight();
            com.pvzce.client.gui.layout.MenuPageCanvas.surface(client(),12,72,previewWidth,h-190,false);
            art.draw(art.item(owner.file(),""),22,owner.file().contains("/dialogue_characters/")?148:126,previewWidth-20,Math.max(30,h-288));
            WorkshopUi.fit(client(),art.item(owner.file(),"").name(),22,105,previewWidth-20,1,1,1,.8F);
            WorkshopUi.fit(client(),"保存后可构建；草稿独立预览",22,85,previewWidth-20,.64F,.7F,.85F,.7F);
        }
        labels.forEach(Runnable::run);
    }
}
