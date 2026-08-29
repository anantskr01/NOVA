package com.aircontrol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Central registry for NOVA capabilities. */
public final class NovaToolRegistry {
    public static final int MAX_VALUE_LENGTH = 4096;
    public static final class Tool {
        public final String type; public final String description; public final boolean requiresConfirmation;
        private Tool(String type,String description,boolean requiresConfirmation){this.type=type;this.description=description;this.requiresConfirmation=requiresConfirmation;}
    }
    private final Map<String,Tool> tools=new LinkedHashMap<>();
    public NovaToolRegistry(){
        register("home","Go to the Android home screen",false); register("back","Navigate back",false); register("recents","Open recent apps",false);
        register("notifications","Open the notification shade",false); register("quick_settings","Open Android quick settings",false);
        register("scroll_up","Scroll upward",false); register("scroll_down","Scroll downward",false); register("swipe_left","Swipe left",false); register("swipe_right","Swipe right",false);
        register("open_url","Open a web URL",false); register("open_package","Open an installed Android package",false); register("open_app","Open an installed application by name",false);
        register("click_text","Activate visible accessibility text",false); register("click_index","Activate a numbered visible accessibility item",false);
        register("type_text","Enter text into a visible editable field; value may be plain text or JSON with target/text",false);
        register("search","Perform a web search",false); register("read_screen","Inspect the currently visible accessibility UI",false); register("verify_screen_contains","Verify visible accessibility UI contains expected text",false);
        register("settings","Open Android settings",false); register("none","No operation",false);
        register("web.search","Search the internet",false); register("web.open","Open a web page",false); register("web.fetch","Fetch readable web content",false);
        register("memory.remember","Store a local memory item",false); register("memory.recall","Recall local memory",false); register("apps.list","List installed launchable applications",false);
        register("files.read","Read an allowed local project file",false); register("files.write","Write an allowed local project file",false); register("files.create","Create an allowed local project file",false);
        register("code.create","Create source code in an allowed project workspace",false); register("code.modify","Modify source code in an allowed project workspace",false);
        register("communication.send_message","Send an external message on the user's behalf",true); register("communication.make_call","Start an external phone call",true);
    }
    private void register(String type,String description,boolean confirmation){tools.put(type,new Tool(type,description,confirmation));}
    public boolean contains(String type){return type!=null&&tools.containsKey(type.trim().toLowerCase(Locale.ROOT));}
    public Tool get(String type){return type==null?null:tools.get(type.trim().toLowerCase(Locale.ROOT));}
    public boolean validate(String type,String value){return contains(type)&&(value==null||value.length()<=MAX_VALUE_LENGTH);}
    public boolean requiresConfirmation(String type){Tool tool=get(type);return tool!=null&&tool.requiresConfirmation;}
    public Set<String> types(){return Collections.unmodifiableSet(new LinkedHashSet<>(tools.keySet()));}
    public String promptSummary(){StringBuilder out=new StringBuilder();for(Tool tool:tools.values()){out.append("- ").append(tool.type).append(": ").append(tool.description);if(tool.requiresConfirmation)out.append(" [CONFIRMATION REQUIRED]");out.append('\n');}return out.toString().trim();}
}
