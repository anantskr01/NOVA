package com.aircontrol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Central registry describing the capabilities available to NOVA's agent. */
public final class NovaToolRegistry {
    private final Map<String, NovaTool> tools = new LinkedHashMap<>();
    public NovaToolRegistry() {
        register(new BasicTool("home","Return to the Android home screen",true));
        register(new BasicTool("back","Navigate back",true)); register(new BasicTool("recents","Open recent apps",true));
        register(new BasicTool("notifications","Open the notification shade",true)); register(new BasicTool("quick_settings","Open quick settings",true));
        register(new BasicTool("scroll_up","Scroll the current UI upward",true)); register(new BasicTool("scroll_down","Scroll the current UI downward",true));
        register(new BasicTool("swipe_left","Swipe the current UI left",true)); register(new BasicTool("swipe_right","Swipe the current UI right",true));
        register(new BasicTool("open_url","Open a web URL",true)); register(new BasicTool("open_package","Launch an installed Android package",true));
        register(new BasicTool("open_app","Launch an installed app by name",true)); register(new BasicTool("click_text","Activate visible UI text",true));
        register(new BasicTool("click_index","Activate a numbered visible UI item",true)); register(new BasicTool("search","Open a web search",true));
        register(new BasicTool("read_screen","Read visible screen text",true)); register(new BasicTool("screen_observe","Observe current screen state",true));
        register(new BasicTool("web_search","Search the public web and return structured results",true));
        register(new BasicTool("web_fetch","Fetch a public page and return bounded readable text",true));
        register(new BasicTool("web_research","Search, inspect results and return a bounded research packet",true));
        register(new BasicTool("memory_search","Search NOVA's saved facts for relevant context",true));
        register(new BasicTool("remember","Save a durable user fact when explicitly requested",true));
        register(new BasicTool("settings","Open Android settings",true)); register(new BasicTool("wait","Wait for a bounded amount of time",true));
        register(new BasicTool("none","Do nothing",true));
    }
    public synchronized void register(NovaTool tool){if(tool==null||tool.type()==null||tool.type().trim().isEmpty())return;tools.put(tool.type().trim().toLowerCase(),tool);}
    public synchronized boolean contains(String type){return type!=null&&tools.containsKey(type.trim().toLowerCase());}
    public synchronized NovaTool get(String type){return type==null?null:tools.get(type.trim().toLowerCase());}
    public synchronized List<NovaTool> all(){return Collections.unmodifiableList(new ArrayList<>(tools.values()));}
    public synchronized String promptSummary(){StringBuilder out=new StringBuilder();for(NovaTool tool:tools.values()){if(out.length()>7000)break;out.append("- ").append(tool.type()).append(": ").append(tool.description()).append("; reversible=").append(tool.reversible()).append('\n');}return out.toString().trim();}
    private static final class BasicTool implements NovaTool{private final String type,description;private final boolean reversible;BasicTool(String t,String d,boolean r){type=t;description=d;reversible=r;}public String type(){return type;}public String description(){return description;}public boolean reversible(){return reversible;}}
}
