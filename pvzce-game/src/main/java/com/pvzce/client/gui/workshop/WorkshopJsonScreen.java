package com.pvzce.client.gui.workshop;

import com.google.gson.*;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import com.pvzce.common.workshop.*;
import java.nio.file.*;
import java.util.*;

/** A lossless, recursive property editor; JSON remains an optional advanced view. */
public final class WorkshopJsonScreen extends Screen {
    private final Workspace workspace;
    private final String file;
    private JsonObject root;
    private JsonObject savedRoot;
    private final Map<List<String>,Integer> pages=new HashMap<>();
    private final List<String> location=new ArrayList<>();
    private final Deque<List<String>> parents=new ArrayDeque<>();
    private int page;
    private String notice="";
    private final Map<String,String> inputErrors=new HashMap<>();
    private final Map<String,String> invalidText=new HashMap<>();
    private boolean advanced;
    private WorkshopPresentation presentation;
    private WorkshopEditorView visual;
    private boolean visualBuilt;
    String file(){return file;}
    JsonObject draft(){return root;}
    void enterClip(String clip){
        var path=new ArrayList<>(List.of("animations",clip));
        if(root.getAsJsonObject("animations").getAsJsonObject(clip).has("frames"))path.add("frames");
        navigate(path);
    }
    String locationKey(){return String.join("/",location);}
    private void navigate(List<String> path){parents.push(List.copyOf(location));showLocation(path);}
    private void showLocation(List<String> path){
        pages.put(List.copyOf(location),page);location.clear();location.addAll(path);
        page=pages.getOrDefault(List.copyOf(location),0);onResize();
    }
    private void parent(){if(!location.isEmpty())showLocation(parents.isEmpty()?new ArrayList<>(location.subList(0,location.size()-1)):parents.pop());}
    boolean atRoot(){return location.isEmpty();}
    String currentField(){return currentKey();}
    void visualWidget(com.pvzce.client.gui.components.AbstractWidget widget){addWidget(widget);}
    void enter(String key){var path=new ArrayList<>(location);path.add(key);navigate(path);}
    void editValue(JsonElement node,String key,JsonElement value){edit(node,key,value);}
    private List<String> pathOf(JsonElement current,JsonElement wanted,List<String> path){
        if(current==wanted)return path;
        if(current.isJsonObject())for(var entry:current.getAsJsonObject().entrySet()){
            var next=new ArrayList<>(path);next.add(entry.getKey());var found=pathOf(entry.getValue(),wanted,next);if(found!=null)return found;
        }
        if(current.isJsonArray())for(int i=0;i<current.getAsJsonArray().size();i++){
            var next=new ArrayList<>(path);next.add(Integer.toString(i));var found=pathOf(current.getAsJsonArray().get(i),wanted,next);if(found!=null)return found;
        }
        return null;
    }
    private String inputKey(JsonElement node,String key){return String.join("/",pathOf(root,node,new ArrayList<>()))+"/"+key;}
    String inputText(JsonElement node,String key,JsonElement value){return invalidText.getOrDefault(inputKey(node,key),value.getAsString());}
    void setText(JsonElement node,String key,JsonElement original,String text){
        try {
            JsonElement value=original.getAsJsonPrimitive().isNumber()?JsonParser.parseString(text):new JsonPrimitive(text);
            if(original.getAsJsonPrimitive().isNumber() && (!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber()||!Double.isFinite(value.getAsDouble())))throw new IllegalArgumentException("请输入有效数字");
            put(node,key,value);if(presentation!=null)presentation.draft(file,root);inputErrors.remove(inputKey(node,key));invalidText.remove(inputKey(node,key));notice="";
        }catch(Exception e){notice=WorkshopUi.label(key)+"：请输入有效数字（尚未应用）";inputErrors.put(inputKey(node,key),notice);invalidText.put(inputKey(node,key),text);}
    }
    private String referenceType(String key){
        if(key.equals("animation")||(!file.contains("/animations/")&&currentKey().equals("animations")))return "animations";
        if(Set.of("texture","night_texture","icon","box_left","box_right").contains(key)||currentKey().equals("frames"))return "textures";
        if(currentKey().equals("sounds")||key.equals("sound")||key.equals("voice"))return "sound_events";
        return switch(key){
            case "projectile" -> "projectiles"; case "resource" -> "resources";
            case "base","plant" -> "plants"; case "zombie" -> "zombies";
            case "underlay" -> "scene_elements"; case "character" -> "dialogue_characters";
            case "content" -> root.has("kind")?switch(root.get("kind").getAsString()){case "zombie"->"zombies";case "tool"->"tools";case "resource"->"resources";default->"plants";}:"plants";
            default -> "";
        };
    }
    boolean isReference(String key){return !referenceType(key).isEmpty();}
    String referenceName(String key,JsonElement value){return value.isJsonPrimitive()?presentation.item(value.getAsString(),referenceType(key)).name():"选择内容";}
    private void pick(String type,java.util.function.Consumer<String> accept){
        Set<String> choices=new TreeSet<>();
        try {
            if(Set.of("textures","animations").contains(type)) {
                for(String path:client.resources().listResources("assets").keySet())if(path.contains("/"+type+"/")&&path.endsWith(type.equals("textures")?".png":".json"))choices.add(referenceId(path,type.equals("animations")));
                for(String path:workspace.files())if(path.contains("/"+type+"/")&&path.endsWith(type.equals("textures")?".png":".json"))choices.add(referenceId(path,type.equals("animations")));
            } else {
                choices.addAll(WorkshopContent.templates(client.resources(),type).keySet());
                for(String path:workspace.files())if(path.startsWith("data/")&&path.contains("/"+type+"/")&&path.endsWith(".json")) {
                    String[] parts=path.split("/",4); choices.add(parts[1]+":"+parts[3].substring(0,parts[3].length()-5));
                }
            }
            client.openScreen(new WorkshopListScreen(client,"选择"+WorkshopUi.label(type),choices,workspace,type,accept));
        }catch(Exception e){notice=e.getMessage();}
    }
    public WorkshopJsonScreen(PvzceClient client,Workspace workspace,String file)throws java.io.IOException {
        super(client);this.workspace=workspace;this.file=file;
        root=JsonParser.parseString(Files.readString(workspace.file(file))).getAsJsonObject();savedRoot=root.deepCopy();
    }
    private JsonElement node(){JsonElement value=root;for(String key:location)value=value.isJsonArray()?value.getAsJsonArray().get(Integer.parseInt(key)):value.getAsJsonObject().get(key);return value;}
    private String currentKey(){return location.isEmpty()?"":location.get(location.size()-1);}
    private void attempt(Runnable action){try{action.run();}catch(Exception e){notice=e.getMessage();}onResize();}
    protected void init(){
        if(presentation==null){presentation=new WorkshopPresentation(client,workspace);visual=new WorkshopEditorView(this,presentation);}
        presentation.draft(file,root);
        int width=client.guiWidth(),height=client.guiHeight(),bw=(width-50)/5;
        addWidget(new Button(12,height-62,bw,26,WorkshopUi.text("save","保存草稿"),()->save()));
        addWidget(new Button(18+bw,height-62,bw,26,WorkshopUi.text("add","添加"),()->attempt(this::add)));
        addWidget(new Button(24+bw*2,height-62,bw,26,"JSON",()->client.openScreen(new WorkshopRawScreen(client,root,changed->{root=changed;inputErrors.clear();invalidText.clear();location.clear();parents.clear();pages.clear();page=0;onResize();}))));
        if(file.contains("/animations/"))addWidget(new Button(30+bw*3,height-62,bw,26,WorkshopUi.text("preview","预览"),()->{if(save())client.openScreen(new WorkshopPreviewScreen(client,workspace,file));}));
        else addWidget(new Button(30+bw*3,height-62,bw,26,WorkshopUi.text("validate","校验"),()->attempt(()->{if(save()){WorkshopAssets.validate(workspace);notice=WorkshopUi.text("valid","校验通过");}})));
        var parent=new Button(36+bw*4,height-62,bw,26,WorkshopUi.text("parent","上一级"),this::parent);
        parent.setActive(!location.isEmpty());addWidget(parent);
        JsonElement visualNode=node();
        visualBuilt=!advanced && visual.build(visualNode,location.isEmpty());
        addWidget(new Button(148,32,128,26,advanced?"可视化编辑":"高级属性",()->{advanced=!advanced;onResize();}));
        if(visualBuilt){
            addWidget(new Button(width-142,32,130,26,WorkshopUi.text("save_back","保存并返回"),()->{if(save())client.popScreen();}));
            return;
        }
        JsonElement node=node();List<String> keys=node.isJsonObject()?new ArrayList<>(node.getAsJsonObject().keySet()):new ArrayList<>();
        if(node.isJsonArray())for(int i=0;i<node.getAsJsonArray().size();i++)keys.add(Integer.toString(i));
        int count=Math.max(1,(height-142)/30);page=Math.min(page,Math.max(0,(keys.size()-1)/count));
        for(int i=page*count;i<Math.min(keys.size(),(page+1)*count);i++){
            String key=keys.get(i);JsonElement value=get(node,key);int y=height-98-(i-page*count)*30;
            String label=WorkshopUi.label(key)+" : "+(value.isJsonObject()?"{…}":value.isJsonArray()?"["+value.getAsJsonArray().size()+"]":value.toString());
            addWidget(new Button(12,y,width-122,26,WorkshopScreen.shorten(label,width-135),()->edit(node,key,value)));
            addWidget(new Button(width-104,y,40,26,"×",()->{if(node.isJsonArray())removeEntry(node.getAsJsonArray(),Integer.parseInt(key));else removeField(node.getAsJsonObject(),key);}));
            if(node.isJsonArray())addWidget(new Button(width-58,y,44,26,"↑",()->moveEntry(node.getAsJsonArray(),Integer.parseInt(key),-1)));
        }
        addWidget(new Button(12,32,60,26,"←",()->{page=Math.max(0,page-1);onResize();}));
        addWidget(new Button(78,32,60,26,"→",()->{if((page+1)*count<keys.size())page++;onResize();}));
        addWidget(new Button(width-112,32,100,26,WorkshopUi.text("save_back","保存并返回"),()->{if(save())client.popScreen();}));
    }
    private static JsonElement get(JsonElement node,String key){return node.isJsonObject()?node.getAsJsonObject().get(key):node.getAsJsonArray().get(Integer.parseInt(key));}
    private static void put(JsonElement node,String key,JsonElement value){if(node.isJsonObject())node.getAsJsonObject().add(key,value);else node.getAsJsonArray().set(Integer.parseInt(key),value);}
    private void edit(JsonElement node,String key,JsonElement value){
        if(currentKey().equals("animation_clips") && value.isJsonPrimitive()) {
            String selected=root.has("animations") && root.getAsJsonObject("animations").has(key)?root.getAsJsonObject("animations").get(key).getAsString():root.has("animation")?root.get("animation").getAsString():null;
            if(selected!=null) {
                try {
                    String path="assets/"+Identifier.parse(selected).namespace()+"/animations/"+Identifier.parse(selected).path()+".json";
                    JsonObject animationJson=java.nio.file.Files.isRegularFile(workspace.file(path))?JsonParser.parseString(java.nio.file.Files.readString(workspace.file(path))).getAsJsonObject():JsonParser.parseString(client.resources().getAsset(Identifier.of(Identifier.parse(selected).namespace(),"animations/"+Identifier.parse(selected).path()+".json")).orElseThrow().readString()).getAsJsonObject();
                    var parsed=com.pvzce.client.animation.AnimationResourceLoader.parse(animationJson,Identifier.parse(selected));
                    client.openScreen(new WorkshopListScreen(client,WorkshopUi.text("clip_binding","选择对应的动画片段"),parsed.clipNames(),clip->{put(node,key,new JsonPrimitive(clip));onResize();}));return;
                }catch(Exception e){notice=e.getMessage();}
            }
        }
        if(value.isJsonPrimitive() && isReference(key)) {
            pick(referenceType(key),id->{put(node,key,new JsonPrimitive(id));onResize();});return;
        }
        if(key.equals("kind") && file.contains("/slots/")) {
            client.openScreen(new WorkshopListScreen(client,"卡片类型",List.of("plant","zombie","resource","tool"),kind->{put(node,key,new JsonPrimitive(kind));onResize();}));return;
        }
        if(value.isJsonObject()||value.isJsonArray()){var path=pathOf(root,node,new ArrayList<>());path.add(key);navigate(path);return;}
        if(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isBoolean()){put(node,key,new JsonPrimitive(!value.getAsBoolean()));onResize();return;}
        WorkshopUi.ask(this,WorkshopUi.label(key),value.isJsonNull()?"":value.getAsString(),text->attempt(()->{
            JsonElement replacement=value.isJsonPrimitive()&&value.getAsJsonPrimitive().isNumber()?JsonParser.parseString(text):new JsonPrimitive(text);
            if(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isNumber()&&(!replacement.isJsonPrimitive()||!replacement.getAsJsonPrimitive().isNumber()))throw new IllegalArgumentException("请输入数字");
            put(node,key,replacement);
        }));
    }
    void addValue(){add();}
    private void add(){
        JsonElement node=node();
        if(visualBuilt&&location.isEmpty()&&visual.addToGroup(root))return;
        if(node.isJsonObject() && currentKey().equals("resources")) {
            pick("resources",id->{node.getAsJsonObject().addProperty(id,0);onResize();});return;
        }
        if(node.isJsonArray()) {
            if(currentKey().equals("capabilities")){addCapability(node.getAsJsonArray());return;}
            if(currentKey().equals("shots")){
                pick("projectiles",id->{JsonObject shot=new JsonObject();shot.addProperty("projectile",id);node.getAsJsonArray().add(shot);onResize();});return;
            }
            if(currentKey().equals("frames")){pick("textures",id->{node.getAsJsonArray().add(id);onResize();});return;}
            JsonArray array=node.getAsJsonArray();
            if(!array.isEmpty()&&(array.get(array.size()-1).isJsonObject()||array.get(array.size()-1).isJsonArray())){
                array.add(array.get(array.size()-1).deepCopy());onResize();return;
            }
            WorkshopUi.ask(this,WorkshopUi.text("value","新成员（ID、标签或 JSON）"),"",value->attempt(()->{
                array.add(value.startsWith("{")||value.startsWith("[")||value.matches("-?[0-9]+(\\.[0-9]+)?")?JsonParser.parseString(value):new JsonPrimitive(value));
            }));return;
        }
        Map<String,JsonElement> defaults=defaults();defaults.keySet().removeAll(node.getAsJsonObject().keySet());
        List<String> choices=new ArrayList<>(defaults.keySet());choices.add(WorkshopUi.text("custom_field","自定义字段"));
        client.openScreen(new WorkshopListScreen(client,WorkshopUi.text("field","添加属性"),choices,key->{
            if(defaults.containsKey(key)){node.getAsJsonObject().add(key,defaults.get(key).deepCopy());onResize();}
            else WorkshopUi.ask(this,WorkshopUi.text("field_name","字段名 / 动画动作名"),"",name->attempt(()->{
                if(name.isBlank()||node.getAsJsonObject().has(name))throw new IllegalArgumentException("字段名为空或已存在");
                if(currentKey().equals("animations")&&file.contains("/animations/")){
                    JsonObject clip=new JsonObject();clip.add("frames",new JsonArray());clip.addProperty("delay",0.1F);clip.addProperty("loop",true);node.getAsJsonObject().add(name,clip);
                }else WorkshopUi.ask(this,WorkshopUi.text("field_value","JSON 值（字符串需要双引号）"),"\"\"",value->attempt(()->node.getAsJsonObject().add(name,JsonParser.parseString(value))));
            }));
        }));
    }
    private Map<String,JsonElement> defaults(){
        Map<String,JsonElement> result=new LinkedHashMap<>();
        String[] parts=file.split("/",4);
        try {
            List<JsonObject> templates=new ArrayList<>(WorkshopContent.templates(client.resources(),parts[2]).values());
            if(location.contains("capabilities"))for(String kind:List.of("plants","zombies"))templates.addAll(WorkshopContent.templates(client.resources(),kind).values());
            for(JsonObject template:templates){
                JsonElement cursor=template;
                for(String key:location){
                    if(cursor==null)break;
                    if(cursor.isJsonObject())cursor=cursor.getAsJsonObject().get(key);
                    else if(cursor.isJsonArray()&&!cursor.getAsJsonArray().isEmpty()) {
                        JsonElement desired=node();
                        if(desired.isJsonObject()&&desired.getAsJsonObject().has("type")) {
                            String type=desired.getAsJsonObject().get("type").getAsString();
                            cursor=java.util.stream.StreamSupport.stream(cursor.getAsJsonArray().spliterator(),false)
                                    .filter(e->e.isJsonObject()&&e.getAsJsonObject().has("type")&&e.getAsJsonObject().get("type").getAsString().equals(type)).findFirst().orElse(null);
                        }else cursor=cursor.getAsJsonArray().get(0);
                    }else cursor=null;
                }
                if(cursor!=null&&cursor.isJsonObject())for(var entry:cursor.getAsJsonObject().entrySet())result.putIfAbsent(entry.getKey(),entry.getValue());
            }
        }catch(Exception e){notice=e.getMessage();}
        if(location.isEmpty()&&(parts[2].equals("plants")||parts[2].equals("zombies"))){
            result.putIfAbsent("health",new JsonPrimitive(300));result.putIfAbsent("capabilities",new JsonArray());
            result.putIfAbsent("texture",new JsonPrimitive("pvzce:textures/mission"));result.putIfAbsent("animation",new JsonPrimitive("pvzce:plant/attacker/pea_shooter"));
            result.putIfAbsent("animations",new JsonObject());result.putIfAbsent("animation_clips",new JsonObject());result.putIfAbsent("sounds",new JsonObject());result.putIfAbsent("render_scale",new JsonPrimitive(1));
        }
        if(!file.contains("/animations/") && (currentKey().equals("animations") || currentKey().equals("animation_clips"))) {
            String animation=root.has("animation")?root.get("animation").getAsString():"pvzce:plant/attacker/pea_shooter";
            for(String state:List.of("idle","shoot","walk","eat","produce","grow","sleep","death","death_burned","arm_raise","explode"))result.putIfAbsent(state,new JsonPrimitive(currentKey().equals("animation_clips")?state:animation));
        }
        if(file.contains("/animations/")){
            if(location.isEmpty()){JsonArray size=new JsonArray();size.add(1);size.add(1);result.put("size",size);JsonArray anchor=new JsonArray();anchor.add(0.5);anchor.add(0);result.put("anchor",anchor);}
            else if(location.size()==2&&location.get(0).equals("animations")){
                result.put("frames",new JsonArray());result.put("delays",new JsonArray());result.put("delay",new JsonPrimitive(0.1));result.put("loop",new JsonPrimitive(true));result.put("on_end",new JsonPrimitive("idle"));result.put("rate",new JsonPrimitive(1));
            }
        }
        return result;
    }
    private void addCapability(JsonArray destination){
        Map<String,JsonObject> options=new TreeMap<>();
        try{for(String kind:List.of(file.contains("/projectiles/")?"projectiles":file.contains("/zombies/")?"zombies":"plants"))for(JsonObject template:WorkshopContent.templates(client.resources(),kind).values()){
            JsonArray caps=template.getAsJsonArray("capabilities");if(caps!=null)for(JsonElement cap:caps)options.putIfAbsent(cap.getAsJsonObject().get("type").getAsString(),cap.getAsJsonObject());
        }}catch(Exception e){throw new IllegalArgumentException(e);}
        boolean plant=file.contains("/plants/");
        var registry=plant?com.pvzce.common.core.BuiltInRegistries.PLANT_CAPABILITIES:file.contains("/projectiles/")?com.pvzce.common.core.BuiltInRegistries.PROJECTILE_CAPABILITIES:com.pvzce.common.core.BuiltInRegistries.ZOMBIE_CAPABILITIES;
        options.keySet().removeIf(id->!registry.containsKey(Identifier.parse(id)));
        for(Identifier id:registry.keySet())if(!options.containsKey(id.toString())){JsonObject cap=new JsonObject();cap.addProperty("type",id.toString());options.put(id.toString(),cap);}
        client.openScreen(new WorkshopListScreen(client,WorkshopUi.text("capability","添加能力"),options.keySet(),workspace,plant?"plant_capabilities":file.contains("/projectiles/")?"projectile_capabilities":"zombie_capabilities",id->{destination.add(options.get(id).deepCopy());onResize();}));
    }
    private static String referenceId(String path,boolean animation){
        String id=assetId(path);
        return animation?id.replace(":animations/",":"):id;
    }
    static String assetId(String path){String[] parts=path.split("/",3);return parts[1]+":"+parts[2].substring(0,parts[2].lastIndexOf('.'));}
    void previewCharacter(){
        presentation.draft(file,root);presentation.activate();
        var id=presentation.item(file,"").id();
        var character=presentation.catalog().get(com.pvzce.common.core.PvzceRegistries.DIALOGUE_CHARACTERS).get(id);
        if(character==null){notice="角色草稿暂不能解析，请检查参数";return;}
        String portrait=id.path();
        try {
            String prefix="assets/"+character.resolvedPortraitDir().toPath()+"/";
            portrait=client.resources().listResources(prefix).keySet().stream().filter(p->p.endsWith(".png")).sorted().map(p->p.substring(prefix.length(),p.length()-4)).findFirst().orElse(portrait);
        }catch(java.io.IOException ignored) { }
        java.util.List<com.pvzce.api.content.DialogueLine> lines=new ArrayList<>();
        for(String side:List.of("left","right")){
            JsonObject line=new JsonObject();line.addProperty("character",id.toString());line.addProperty("portrait",portrait);line.addProperty("side",side);
            line.addProperty("text",character.displayName()+"的对话框预览：这是"+(side.equals("left")?"左":"右")+"侧站位，点击查看下一侧。");
            com.pvzce.api.content.DialogueLine.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,line).result().ifPresent(lines::add);
        }
        client.openScreen(new com.pvzce.client.gui.screens.DialoguePreviewScreen(client,new com.pvzce.api.content.LevelDialogue(lines),presentation.catalog()));
    }
    private JsonObject frameClip(JsonArray array){
        var path=pathOf(root,array,new ArrayList<>());
        if(path==null||path.isEmpty()||!path.get(path.size()-1).equals("frames"))return null;
        JsonElement value=root;for(String key:path.subList(0,path.size()-1))value=value.isJsonObject()?value.getAsJsonObject().get(key):value.getAsJsonArray().get(Integer.parseInt(key));
        return value.isJsonObject()?value.getAsJsonObject():null;
    }
    private JsonArray frameDelays(JsonArray frames){
        JsonObject clip=frameClip(frames);if(clip==null)return null;
        JsonArray delays=clip.has("delays")?clip.getAsJsonArray("delays"):new JsonArray();
        double fallback=clip.has("delay")?clip.get("delay").getAsDouble():0.1;
        JsonArray expanded=new JsonArray();
        for(int i=0;i<frames.size();i++)expanded.add(delays.isEmpty()?new JsonPrimitive(fallback):delays.get(Math.min(i,delays.size()-1)).deepCopy());
        return expanded;
    }
    private void reindexInputs(JsonArray array,java.util.function.IntUnaryOperator indexMap){
        String prefix=String.join("/",pathOf(root,array,new ArrayList<>()))+"/";
        for(var state:List.of(inputErrors,invalidText)){
            Map<String,String> updated=new HashMap<>();
            for(var entry:state.entrySet()){
                if(!entry.getKey().startsWith(prefix)){updated.put(entry.getKey(),entry.getValue());continue;}
                String suffix=entry.getKey().substring(prefix.length());int slash=suffix.indexOf('/');
                String first=slash<0?suffix:suffix.substring(0,slash);
                int previous;try{previous=Integer.parseInt(first);}catch(NumberFormatException e){updated.put(entry.getKey(),entry.getValue());continue;}
                int next=indexMap.applyAsInt(previous);if(next>=0)updated.put(prefix+next+(slash<0?"":suffix.substring(slash)),entry.getValue());
            }
            state.clear();state.putAll(updated);
        }
    }
    void moveEntry(JsonArray array,int index,int delta){
        int next=index+delta;if(next<0||next>=array.size())return;
        reindexInputs(array,i->i==index?next:i==next?index:i);
        JsonArray delays=frameDelays(array);
        JsonElement previous=array.get(next);array.set(next,array.get(index));array.set(index,previous);
        if(delays!=null){previous=delays.get(next);delays.set(next,delays.get(index));delays.set(index,previous);frameClip(array).add("delays",delays);}
        onResize();
    }
    void removeEntry(JsonArray array,int index){
        reindexInputs(array,i->i==index?-1:i>index?i-1:i);
        JsonObject clip=frameClip(array);JsonArray delays=frameDelays(array);
        array.remove(index);if(delays!=null){delays.remove(index);clip.add("delays",delays);}onResize();
    }
    String frameDelay(JsonArray frames,int index){var delays=frameDelays(frames);return delays==null?"":delays.get(index).getAsString()+" 秒";}
    void editFrameDelay(JsonArray frames,int index){
        var delays=frameDelays(frames);if(delays==null)return;
        WorkshopUi.ask(this,"第 "+(index+1)+" 帧时长（秒）",delays.get(index).getAsString(),text->attempt(()->{
            double seconds=Double.parseDouble(text);if(!Double.isFinite(seconds)||seconds<=0)throw new IllegalArgumentException("帧时长必须大于零");
            delays.set(index,new JsonPrimitive(seconds));frameClip(frames).add("delays",delays);
        }));
    }
    void removeField(JsonObject node,String key){String prefix=inputKey(node,key);node.remove(key);inputErrors.keySet().removeIf(k->k.equals(prefix)||k.startsWith(prefix+"/"));invalidText.keySet().removeIf(k->k.equals(prefix)||k.startsWith(prefix+"/"));onResize();}
    private boolean save(){if(!inputErrors.isEmpty()){notice=inputErrors.values().iterator().next();return false;}try{workspace.write(file,root);savedRoot=root.deepCopy();notice=WorkshopUi.text("saved","草稿已保存，尚未构建");return true;}catch(Exception e){notice=e.getMessage();return false;}}
    public void requestClose(){if(!location.isEmpty())parent();else if(save())super.requestClose();}
    protected void onHidden(){if(presentation!=null)presentation.stopAudition();}
    protected void onRemoved(){if(presentation!=null)presentation.close();}
    public void render(){renderBackground(0.08F,0.12F,0.1F);WorkshopUi.backdrop(client);
        String trail=location.stream().map(k->k.matches("[0-9]+")?"#"+(Integer.parseInt(k)+1):WorkshopUi.label(k)).collect(java.util.stream.Collectors.joining(" / "));
        String state=root.equals(savedRoot)&&inputErrors.isEmpty()?"已保存":"未保存";
        WorkshopUi.fit(client,file.substring(file.lastIndexOf('/')+1)+" · "+state+" / "+(trail.isEmpty()?"概览":trail),12,client.guiHeight()-28,client.guiWidth()-24,.9F,1,1,.8F);
        client.fonts().body().draw(WorkshopScreen.shorten(notice,client.guiWidth()-24),12,10,0.8F,1,0.85F,0.6F,1);
        if(visualBuilt)visual.render();
        for(var widget:widgets)widget.render(client);
    }
}
