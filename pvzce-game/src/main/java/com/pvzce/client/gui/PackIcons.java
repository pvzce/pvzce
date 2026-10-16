package com.pvzce.client.gui;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.renderer.texture.Texture;
import com.pvzce.common.resource.PackResource;
import com.pvzce.common.resource.PvzceResourceManager.AvailablePack;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Pack-root icons stay independent of the loaded asset overlay and belong to their GUI. */
public final class PackIcons implements AutoCloseable {
    private static final AtomicLong OWNERS = new AtomicLong();
    private final long owner = OWNERS.incrementAndGet();
    private final PvzceClient client;
    private final Map<String, Icon> icons = new HashMap<>();
    private static final class Icon {
        final Identifier id;
        final PackResource resource;
        boolean broken;
        Icon(Identifier id,PackResource resource){this.id=id;this.resource=resource;}
    }
    public PackIcons(PvzceClient client){this.client=client;}

    public void draw(Path root,float x,float y,float side){
        draw(new AvailablePack(root.getFileName().toString(),AvailablePack.Kind.DATAPACK,false,root,""),x,y,side);
    }
    public void draw(AvailablePack pack,float x,float y,float side){
        String key=pack.kind()+":"+(pack.path()==null?pack.name():pack.path().toAbsolutePath().normalize());
        Icon icon=icons.computeIfAbsent(key,k->new Icon(Identifier.of("pack_icon",owner+"/"+icons.size()),
                client.resources()==null?null:client.resources().packIcon(pack).orElse(null)));
        Texture texture=null;
        if(icon.resource!=null&&!icon.broken&&client.textures()!=null){
            texture=client.textures().get(icon.id);
            if(texture==null)try{texture=client.textures().uploadAndCache(icon.id,icon.resource);}catch(RuntimeException e){icon.broken=true;}
        }
        client.drawSolid(x,y,side,side,0,.16F,.25F,.18F,1);
        if(texture!=null){
            float scale=Math.min(side/texture.width(),side/texture.height());
            client.drawTexture(icon.id,x+(side-texture.width()*scale)/2,y+(side-texture.height()*scale)/2,
                    texture.width()*scale,texture.height()*scale,0,1,1,1,1);
        }else{
            float scale=Math.min(1.5F,side/28F);
            String label="包";
            client.fonts().body().draw(label,x+(side-client.fonts().body().width(label,scale))/2,y+side*.34F,scale,.8F,.9F,.65F,1);
        }
    }
    public void refresh(){
        if(client.textures()!=null)for(Icon icon:icons.values())client.textures().release(icon.id);
        icons.clear();
    }
    @Override public void close(){refresh();}
}
