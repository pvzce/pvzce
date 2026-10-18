package com.pvzce.client.gui.workshop;
import com.google.gson.*;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.*;
import com.pvzce.common.workshop.Workspace;
import org.lwjgl.glfw.GLFW;
import java.util.*;
import java.util.function.Consumer;

/** Optional multiline JSON source editing, with cursor movement and paste. */
final class WorkshopRawScreen extends Screen {
    private final Consumer<JsonObject> accept;
    private String source;
    private String error="";
    private SourceBox editor;
    WorkshopRawScreen(PvzceClient client,JsonObject json,Consumer<JsonObject> accept){super(client);source=Workspace.JSON.toJson(json);this.accept=accept;}
    protected void init(){
        if(editor!=null)source=editor.value();
        editor=new SourceBox(12,60,client.guiWidth()-24,Math.max(60,client.guiHeight()-110));editor.setValue(source,false);addWidget(editor);
        addWidget(new Button(12,20,140,28,WorkshopUi.text("apply","应用 JSON"),()->{
            try{JsonObject parsed=JsonParser.parseString(editor.value()).getAsJsonObject();client.popScreen();accept.accept(parsed);}catch(Exception e){error=e.getMessage();}
        }).style(Button.Style.SEED_CHOOSER));
        addWidget(new Button(client.guiWidth()-112,20,100,28,WorkshopUi.text("cancel","取消"),this::requestClose).style(Button.Style.SEED_CHOOSER));
    }
    public void render(){WorkshopUi.backdrop(client, this);
        client.fonts().body().draw(WorkshopScreen.shorten(error.isEmpty()?"JSON":error,client.guiWidth()-24),12,client.guiHeight()-30,0.9F,1,0.9F,0.7F,1);
        for(var widget:widgets)widget.render(client);
    }
    private static final class SourceBox extends EditBox {
        private int cursor,scroll;
        SourceBox(int x,int y,int width,int height){super(x,y,width,height,1048576,null);}
        public void setValue(String value,boolean notify){super.setValue(value,notify);cursor=Math.min(cursor,value().length());}
        private void insert(String text){if(value().length()+text.length()>1048576)return;int next=cursor+text.length();super.setValue(value().substring(0,cursor)+text+value().substring(cursor),false);cursor=next;}
        public boolean appendText(String text){insert(text.replace("\r", ""));return true;}
        public boolean charTyped(char ch){if(!isFocused())return false;if(ch>=32)insert(Character.toString(ch));return true;}
        public boolean keyPressed(int key){if(!isFocused())return false;
            switch(key){
                case GLFW.GLFW_KEY_ENTER,GLFW.GLFW_KEY_KP_ENTER -> insert("\n");
                case GLFW.GLFW_KEY_TAB -> insert("  ");
                case GLFW.GLFW_KEY_LEFT -> cursor=Math.max(0,cursor-1);
                case GLFW.GLFW_KEY_RIGHT -> cursor=Math.min(value().length(),cursor+1);
                case GLFW.GLFW_KEY_HOME -> cursor=Math.max(0,value().lastIndexOf('\n',Math.max(0,cursor-1))+1);
                case GLFW.GLFW_KEY_END -> {int end=value().indexOf('\n',cursor);cursor=end<0?value().length():end;}
                case GLFW.GLFW_KEY_UP -> scroll=Math.max(0,scroll-1);
                case GLFW.GLFW_KEY_DOWN -> scroll++;
                case GLFW.GLFW_KEY_BACKSPACE -> {if(cursor>0){super.setValue(value().substring(0,cursor-1)+value().substring(cursor),false);cursor--;}}
                case GLFW.GLFW_KEY_DELETE -> {if(cursor<value().length())super.setValue(value().substring(0,cursor)+value().substring(cursor+1),false);}
                default -> {return false;}
            }return true;
        }
        public void mouseScrolled(double mx,double my,double amount){scroll=Math.max(0,scroll-(int)amount*3);}
        public void render(PvzceClient client){
            renderBackground(client,0.02F,0.03F,0.03F,0.95F);
            List<String> lines=new ArrayList<>();StringBuilder line=new StringBuilder();
            for(int i=0;i<=value().length();i++){
                if(i==cursor&&isFocused())line.append('|');
                if(i==value().length()){lines.add(line.toString());break;}
                char ch=value().charAt(i);
                if(ch=='\n'||client.fonts().body().width(line.toString()+ch,0.8F)>width-18){lines.add(line.toString());line.setLength(0);if(ch=='\n')continue;}
                line.append(ch);
            }
            int count=Math.max(1,(height-12)/17);scroll=Math.min(scroll,Math.max(0,lines.size()-count));
            client.clipping().push(x,y,width,height);
            try{for(int i=scroll;i<Math.min(lines.size(),scroll+count);i++)client.fonts().body().draw(lines.get(i),x+6,y+height-22-(i-scroll)*17,0.8F,0.85F,1,0.85F,1);}finally{client.clipping().pop();}
        }
    }
}
