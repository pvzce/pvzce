package com.pvzce.client.gui.editor;

import com.google.gson.*;
import com.pvzce.api.content.mechanic.TypedMechanic;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.common.core.*;
import com.pvzce.common.level.mechanic.LevelMechanics;
import com.mojang.serialization.JsonOps;
import java.util.*;

/** The optional editor entrances; existing level blocks, rather than a second gameplay schema, enable them. */
public final class EditorModules {
    public static final Set<String> BASE = Set.of("overview","terrain","plant","zombie","info");
    private static final Map<String,List<String>> FIELDS = Map.of(
            "rule",List.of("rules"), "wave",List.of("waves","wave_interval_end_multiplier"),
            "cards",List.of("slots","max_seed_slots"), "music",List.of("music"),
            "dialogue",List.of("dialogue"), "unlock",List.of("unlock"),
            "look",List.of("background","hidden_scene_elements","disable_shaders"));
    private final Set<String> enabled = new LinkedHashSet<>();
    private final Set<String> hidden = new HashSet<>();
    public record Addition(String key,String label,JsonObject mechanic) {}

    public EditorModules(JsonObject json){
        for(var entry:FIELDS.entrySet())if(entry.getValue().stream().anyMatch(json::has))enabled.add(entry.getKey());
        if(hasMechanic(json,"pvzce:rhythm"))enabled.add("rhythm");
        if(hasMechanic(json,"pvzce:deck"))enabled.add("cards");
    }
    public boolean active(EditorPage page){
        return BASE.contains(page.id())||enabled.contains(page.id())||page.id().startsWith("mechanic.")||!optional(page.id());
    }
    public boolean shown(EditorPage page){return active(page)&&!hidden.contains(page.id());}
    public static boolean optional(String id){return FIELDS.containsKey(id)||id.equals("rhythm")||id.startsWith("mechanic.");}
    public void show(String id){enabled.add(id);hidden.remove(id);}
    public void hide(String id){if(!BASE.contains(id))hidden.add(id);}
    public static boolean hasMechanic(JsonObject json,String id){
        if(!json.has("mechanics")||!json.get("mechanics").isJsonArray())return false;
        for(var value:json.getAsJsonArray("mechanics"))if(value.isJsonObject()&&value.getAsJsonObject().has("type")&&value.getAsJsonObject().get("type").isJsonPrimitive()&&value.getAsJsonObject().get("type").getAsString().equals(id))return true;
        return false;
    }
    public static String mechanicPage(Identifier id){return "mechanic."+id.path();}
    public static String mechanicLabel(Identifier id){return GuiLang.raw("pvzce.mechanic."+id.path(),GuiLang.name("level_mechanic",id));}

    /** Defaults come from each codec; mechanics requiring data borrow an existing valid level block. */
    public List<Addition> additions(EditorContext context,List<EditorPage> pages){
        List<Addition> result=new ArrayList<>();
        for(EditorPage page:pages)if(optional(page.id())&&!shown(page)&&page.visibleFor(context))result.add(new Addition(page.id(),page.label(),null));
        Map<Identifier,JsonObject> templates=new HashMap<>();
        for(var level:context.screen().editorCatalog().get(PvzceRegistries.LEVELS))for(TypedMechanic typed:level.mechanics()){
            LevelMechanics.codec().encodeStart(JsonOps.INSTANCE,typed).result().filter(JsonElement::isJsonObject)
                    .ifPresent(value->templates.putIfAbsent(typed.type(),value.getAsJsonObject()));
        }
        for(Identifier id:BuiltInRegistries.LEVEL_MECHANICS.keySet()){
            if(hasMechanic(context.draft().json(),id.toString()))continue;
            JsonObject minimal=new JsonObject();minimal.addProperty("type",id.toString());
            JsonObject block=LevelMechanics.codec().parse(JsonOps.INSTANCE,minimal).result()
                    .flatMap(value->LevelMechanics.codec().encodeStart(JsonOps.INSTANCE,value).result())
                    .filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject).orElse(templates.get(id));
            if(block==null)continue;
            String label=mechanicLabel(id);
            result.add(new Addition("add_mechanic:"+id,label,block.deepCopy()));
        }
        return List.copyOf(result);
    }
    public void add(EditorContext context,Addition addition){
        if(addition.mechanic()==null){show(addition.key());return;}
        JsonObject block=addition.mechanic().deepCopy();String type=block.get("type").getAsString();
        if(hasMechanic(context.draft().json(),type)){show(mechanicPage(Identifier.parse(type)));return;}
        var mechanic=LevelMechanics.get(Identifier.parse(type));
        if(mechanic!=null&&mechanic.cardSource())for(var existing:context.draft().getArray("mechanics")){
            if(!existing.isJsonObject()||!existing.getAsJsonObject().has("type"))continue;
            var other=LevelMechanics.get(Identifier.tryParse(existing.getAsJsonObject().get("type").getAsString()));
            if(other!=null&&other.cardSource())throw new IllegalArgumentException("这一关已有卡源机制，请先在高级 JSON 中调整卡源");
        }
        JsonArray mechanics=context.draft().getArray("mechanics");mechanics.add(block);context.draft().set("mechanics",mechanics);
        if(type.equals("pvzce:rhythm"))show("rhythm");
        if(mechanic!=null&&mechanic.cardSource()&&!mechanic.dealsItsOwnCards())show("cards");
        show(mechanicPage(Identifier.parse(type)));
    }
}
