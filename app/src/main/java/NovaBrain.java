package com.aircontrol;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Central NOVA reasoning layer: context -> model -> tools -> model -> verification -> memory. */
public final class NovaBrain {
    private static final String TAG = "NovaBrain";
    private static final String PREFS = "nova_ai_settings";
    private static final String ENDPOINT = "endpoint";
    private static final String MODEL = "model";
    private static final int MAX_QUEUE = 6;
    private static final int MAX_AGENT_TURNS = 8;
    private static final int MAX_RELEVANT_FACTS = 8;

    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_FAILED = "failed";
    public static final String OUTCOME_CANCELLED = "cancelled";
    public static final String OUTCOME_WAITING_USER = "waiting_user";

    public interface Listener { void onStatus(String text); void onReply(String text); }
    public interface GoalOutcomeListener { void onGoalFinished(String goal, String outcome, String message); }

    private final Context context;
    private final Listener listener;
    private final NovaMemory memory;
    private final NovaSecureStore secureStore;
    private final NovaAiClient ai = new NovaAiClient();
    private final NovaActionEngine actions;
    private final NovaAgentPlanner planner;
    private final NovaToolRegistry tools;
    private final NovaWebIntelligence web = new NovaWebIntelligence();
    private final NovaTaskOrchestrator orchestrator = new NovaTaskOrchestrator();
    private final ExecutorService agentExecutor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Deque<String> queue = new ArrayDeque<>();
    private boolean processing;
    private boolean shutdown;
    private long generation;
    private String activeGoal = "";
    private GoalOutcomeListener goalOutcomeListener;

    public NovaBrain(Context c, NovaActionEngine a, NovaMemory m, Listener l) {
        context = c.getApplicationContext(); listener = l; actions = a;
        memory = m == null ? new NovaMemory(context) : m;
        secureStore = new NovaSecureStore(context); tools = new NovaToolRegistry();
        planner = new NovaAgentPlanner(new NovaAgentPlanner.ActionExecutor() {
            public boolean execute(String t,String v){return NovaBrain.this.actions!=null&&NovaBrain.this.actions.execute(t,v);}
            public String readScreen(){GestureAccessibilityService s=GestureAccessibilityService.getInstance();return s==null?"Accessibility service is not connected.":s.getVisibleTextSummary();}
            public String readUiState(){return getUiSnapshot();}
            public String activePackageName(){GestureAccessibilityService s=GestureAccessibilityService.getInstance();return s==null?"":s.getActivePackageName();}
            public boolean clickText(String t){return GestureAccessibilityService.getInstance()!=null&&GestureAccessibilityService.getInstance().clickText(t);}
            public boolean clickVisibleIndex(int i){return GestureAccessibilityService.getInstance()!=null&&GestureAccessibilityService.getInstance().clickVisibleIndex(i);}
            public String executeTool(String t,String v){return executeIntelligenceTool(t,v);}
            public String executeParallel(String v){return executeParallelTools(v);}
        }, new NovaAgentPlanner.Listener(){public void status(String t){NovaBrain.this.status(t);}public void reply(String t){NovaBrain.this.reply(t);}}, tools);
    }

    public synchronized void setGoalOutcomeListener(GoalOutcomeListener listener) { goalOutcomeListener = listener; }

    public synchronized void think(String request){
        if(shutdown||request==null||request.trim().isEmpty())return;
        if(getEndpoint().isEmpty()){reply("My AI core isn't configured yet.");return;}
        if(queue.size()>=MAX_QUEUE){reply("My task queue is full. Cancel or finish a task before adding another.");return;}
        queue.offer(request.trim());
        NovaDiagnostics.event("goal_created", "queued=" + queue.size());
        status(processing?"BRAIN • GOAL QUEUED • "+queue.size():"BRAIN • GOAL ACCEPTED"); processNextLocked();
    }
    public synchronized void cancelAllGoals(){
        generation++; String cancelledGoal=activeGoal; queue.clear(); activeGoal=""; processing=false;
        if(!cancelledGoal.isEmpty()){NovaDiagnostics.event("goal_cancelled", "active"); notifyGoalFinished(cancelledGoal,OUTCOME_CANCELLED,"Goal cancelled by user.");}
        status("BRAIN • TASKS CANCELLED");
    }
    public synchronized void cancelQueuedGoals(){ queue.clear(); status("BRAIN • QUEUED GOALS CANCELLED"); }
    public synchronized int queuedCount(){return queue.size();}
    public synchronized boolean isBusy(){return processing;}
    public synchronized String activeGoal(){return activeGoal;}

    private void processNextLocked(){if(processing||shutdown)return;activeGoal=queue.poll();if(activeGoal==null)return;processing=true;NovaDiagnostics.event("goal_started", "active");memory.remember("user",activeGoal);askAi(activeGoal,0,"",generation,0,System.currentTimeMillis());}

    private void askAi(final String goal,final int recoveryAttempt,final String feedback,final long token,final int turn,final long goalStarted){
        synchronized(this){if(shutdown||token!=generation)return;}
        if(NovaAgentPolicy.taskExpired(goalStarted)){rememberAndReply("I stopped safely because the task exceeded NOVA's execution time limit.");finishGoal(token,OUTCOME_FAILED,"Task execution time limit exceeded.");return;}
        if(turn>=MAX_AGENT_TURNS){rememberAndReply("I stopped safely after reaching the agent reasoning limit.");finishGoal(token,OUTCOME_FAILED,"Agent reasoning limit reached.");return;}
        status(recoveryAttempt>0?"BRAIN • RECOVERING → REPLANNING":turn==0?"BRAIN • UNDERSTANDING → PLANNING":"BRAIN • OBSERVING → NEXT STEP");
        if(recoveryAttempt>0) NovaDiagnostics.event("recovery", "replan_attempt=" + recoveryAttempt);
        try{
            JSONArray messages=new JSONArray();
            messages.put(new JSONObject().put("role","system").put("content",buildSystemPrompt(recoveryAttempt>0)));
            StringBuilder contextText=new StringBuilder("Relevant saved NOVA memory:\n").append(memory.searchFacts(goal,MAX_RELEVANT_FACTS)).append("\n\nCurrent UI state:\n").append(NovaAgentPolicy.bounded(getUiSnapshot(),NovaAgentPolicy.MAX_TOOL_RESULT_CHARS));
            if(!feedback.isEmpty())contextText.append("\n\nPrevious tool/execution evidence:\n").append(NovaAgentPolicy.bounded(feedback,NovaAgentPolicy.MAX_TOOL_RESULT_CHARS));
            messages.put(new JSONObject().put("role","system").put("content",NovaAgentPolicy.bounded(contextText.toString(),NovaAgentPolicy.MAX_TOOL_RESULT_CHARS)));
            JSONArray history=memory.recent();int start=Math.max(0,history.length()-NovaAgentPolicy.MAX_CONTEXT_ITEMS);for(int i=start;i<history.length();i++){JSONObject item=history.optJSONObject(i);if(item!=null)messages.put(item);}
            String endpoint=getEndpoint(); String provider=ai.providerId(endpoint);
            NovaDiagnostics.event("provider_selected", provider);
            status("BRAIN • PROVIDER " + provider);
            ai.chat(endpoint,secureStore.getApiKey(),getModel(),messages,new NovaAiClient.Callback(){
                public void onResult(final String text){agentExecutor.execute(()->{
                    synchronized(NovaBrain.this){if(shutdown||token!=generation)return;}
                    NovaAgentPlanner.ExecutionResult r=planner.executeDetailed(text);
                    synchronized(NovaBrain.this){if(shutdown||token!=generation)return;}
                    if(!r.planValid){NovaDiagnostics.event("plan_invalid", NovaDiagnostics.compact(r.failedAction));if(recoveryAttempt<1)main.post(()->askAi(goal,recoveryAttempt+1,"Invalid plan: "+r.failedAction,token,turn+1,goalStarted));else{rememberAndReply("I couldn't produce a safe executable plan.");finishGoal(token,OUTCOME_FAILED,"Invalid executable plan after recovery.");}return;}
                    if(!r.toolResults.isEmpty()){NovaDiagnostics.event("tool_executed", "results_present");main.post(()->askAi(goal,0,r.toolResults,token,turn+1,goalStarted));return;}
                    if(r.completed){if(prematureCompletion(goal,r.finalScreen)){NovaDiagnostics.event("verification_failed", "premature_completion");main.post(()->askAi(goal,0,"NOVA must not claim this goal is complete yet. Re-observe the UI and continue with the next necessary action. Current UI:\n"+NovaAgentPolicy.bounded(r.finalScreen,NovaAgentPolicy.MAX_TOOL_RESULT_CHARS),token,turn+1,goalStarted));return;}if(!r.say.isEmpty())rememberAndReply(r.say);finishGoal(token,OUTCOME_SUCCESS,r.say);return;}
                    if(recoveryAttempt<1){NovaDiagnostics.event("recovery", "failed_action");String failure="Failed action: "+r.failedAction+"\nObserved UI after failure:\n"+r.finalScreen;main.post(()->askAi(goal,recoveryAttempt+1,failure,token,turn+1,goalStarted));}else{String message=r.failedAction.isEmpty()?"I couldn't complete that task safely.":"I couldn't complete the task safely at: "+r.failedAction+".";rememberAndReply(message);finishGoal(token,OUTCOME_FAILED,message);}
                });}
                public void onError(String message){synchronized(NovaBrain.this){if(shutdown||token!=generation)return;}String kind=NovaAiProviderManager.classifyFailure(message);NovaDiagnostics.event("provider_failure", kind);String replyText="My AI core is unavailable right now. "+message;rememberAndReply(replyText);finishGoal(token,OUTCOME_FAILED,replyText);}
            });
        }catch(Exception e){Log.e(TAG,"AI REQUEST PREPARATION ERROR",e);NovaDiagnostics.event("goal_failure", "request_preparation");String message="I couldn't prepare the AI request.";rememberAndReply(message);finishGoal(token,OUTCOME_FAILED,message);}
    }

    private String buildSystemPrompt(boolean recovery){StringBuilder p=new StringBuilder();p.append("You are NOVA, a careful general-purpose Android agent. Understand the user's goal, inspect the current UI state, choose the smallest correct next action, and return JSON only. Never claim success without evidence. A goal is NOT complete merely because an app was opened; every requested outcome must be observed and verified. Use at most ").append(NovaAgentPolicy.MAX_STEPS).append(" actions per reasoning turn and at most ").append(MAX_AGENT_TURNS).append(" reasoning turns per goal.\n");p.append("Schema: {\"say\":\"short final response\",\"actions\":[{\"type\":\"tool\",\"value\":\"value\"}]}. If more work is needed, issue the next tool call instead of claiming completion. For multi-step UI goals, use ONE state-changing Android action per turn. After that action, rely on the fresh UI state supplied on the next reasoning turn. Do not assume a coordinate, button position, or previous screen remains valid after UI changes. Prefer semantic UI targeting with click_text when a visible label/content description identifies the intended control. Use click_index only when the current observed UI clearly provides a reliable numbered target. Never choose an action solely from memory when the current UI contradicts it. Keep web-dependent actions in separate turns so returned evidence can be inspected.\n");p.append("Available tools:\n").append(tools.promptSummary());p.append("\nObservation rules: screen_observe/read_screen describe the current accessibility UI tree. Treat that observation as potentially time-sensitive and re-observe after UI mutations. Use memory_search only for relevant saved facts. Use remember only for durable facts/preferences explicitly provided by the user. Use parallel only for independent informational tools; never parallelize Android UI mutations. Prefer reversible actions and stop for unavailable permissions/authentication.");if(recovery)p.append("\nRecovery mode: diagnose the supplied failure/evidence, re-observe the current UI, and choose a meaningfully different safe approach. Do not blindly repeat a failed action.");return p.toString();}

    private boolean prematureCompletion(String goal,String screen){String g=goal==null?"":goal.trim().toLowerCase();String s=screen==null?"":screen.trim().toLowerCase();if(g.isEmpty()||s.isEmpty())return false;if(g.matches(".*\\b(search|find|look up)\\b.*")){String phrase=extractSearchPhrase(g);if(!phrase.isEmpty()){String[] tokens=phrase.split("\\s+");int meaningful=0,matched=0;for(String token:tokens){String t=token.replaceAll("[^a-z0-9]","");if(t.length()<4)continue;meaningful++;if(s.contains(t))matched++;}if(meaningful>1&&matched<meaningful)return true;if(meaningful==1&&matched==0)return true;}}return false;}
    private String extractSearchPhrase(String goal){String g=goal==null?"":goal.toLowerCase().replaceAll("[?.!]"," ");java.util.regex.Matcher m=java.util.regex.Pattern.compile("\\b(?:search(?: for)?|find|look up)\\s+(.+?)(?:\\s+(?:on|in|using)\\s+.+)?$").matcher(g);return m.find()?m.group(1).trim():"";}
    private String executeIntelligenceTool(String type,String value){try{if("web_search".equals(type))return ok(web.search(value,5));if("web_fetch".equals(type))return ok(web.fetch(value));if("web_research".equals(type))return ok(web.search(value,6));if("screen_observe".equals(type)||"read_screen".equals(type))return "{\"ok\":true,\"text\":\""+escape(NovaAgentPolicy.bounded(getUiSnapshot(),NovaAgentPolicy.MAX_TOOL_RESULT_CHARS))+"\"}";if("memory_search".equals(type))return "{\"ok\":true,\"facts\":"+memory.searchFacts(value,8)+"}";if("remember".equals(type)){JSONObject o=new JSONObject(value);String k=o.optString("key","").trim(),v=o.optString("value","").trim();if(k.isEmpty()||v.isEmpty())return "{\"ok\":false,\"error\":\"key_and_value_required\"}";memory.rememberFact(k,v);return "{\"ok\":true,\"saved\":true}";}return "{\"ok\":false,\"error\":\"unknown_intelligence_tool\"}";}catch(Exception e){return "{\"ok\":false,\"error\":\""+escape(e.getMessage()==null?"tool_failed":e.getMessage())+"\"}";}}
    private String executeParallelTools(String value){try{JSONArray steps=new JSONArray(value);if(steps.length()==0||steps.length()>NovaAgentPolicy.MAX_STEPS)return "{\"ok\":false,\"error\":\"parallel_step_limit\"}";for(int i=0;i<steps.length();i++){JSONObject s=steps.optJSONObject(i);if(s==null)return "{\"ok\":false,\"error\":\"parallel_invalid_step\"}";String type=s.optString("type","").trim().toLowerCase();String validation=NovaActionSchema.validate(s);if(!NovaActionSchema.isInformational(type)||!NovaActionSchema.canRunInParallel(type)||!validation.isEmpty())return "{\"ok\":false,\"error\":\"parallel_only_allows_valid_informational_tools\",\"detail\":\""+escape(validation.isEmpty()?type:validation)+"\"}";}JSONArray out=orchestrator.executeParallel(toOrchestratorSteps(steps),(tool,input)->executeToolJson(tool,input));return "{\"ok\":true,\"parallel_results\":"+out+"}";}catch(Exception e){return "{\"ok\":false,\"error\":\"parallel_failed\"}";}}
    private JSONArray toOrchestratorSteps(JSONArray source)throws Exception{JSONArray out=new JSONArray();for(int i=0;i<source.length();i++){JSONObject s=source.optJSONObject(i);if(s!=null)out.put(new JSONObject().put("id",s.optString("id",String.valueOf(i))).put("tool",s.optString("type","")).put("input",new JSONObject().put("value",s.optString("value",""))));}return out;}
    private JSONObject executeToolJson(String tool,JSONObject input){try{return new JSONObject(executeIntelligenceTool(tool,input==null?"":input.optString("value","")));}catch(Exception e){try{return new JSONObject().put("ok",false).put("error","tool_failed");}catch(Exception ignored){return new JSONObject();}}}
    private String ok(String payload){try{JSONObject o=new JSONObject(payload);o.put("ok",true);return o.toString();}catch(Exception e){return "{\"ok\":true,\"data\":\""+escape(payload)+"\"}";}}
    private String escape(String s){return s==null?"":s.replace("\\","\\\\").replace("\"","\\\"").replace("\r"," ").replace("\n"," ");}
    private void finishGoal(long token,String outcome,String message){GoalOutcomeListener callback;String goal; synchronized(this){if(shutdown||token!=generation)return;goal=activeGoal;processing=false;activeGoal="";callback=goalOutcomeListener;}NovaDiagnostics.event("goal_finished", outcome);memory.rememberEpisode("", goal + " -> " + outcome, message); if(callback!=null&&!goal.isEmpty()){try{callback.onGoalFinished(goal,outcome,message==null?"":message);}catch(Exception e){Log.e(TAG,"Goal outcome listener failed",e);}} synchronized(this){if(shutdown||token!=generation)return;if(!queue.isEmpty()){status("BRAIN • NEXT GOAL");processNextLocked();}else status("BRAIN • IDLE");}}
    private void notifyGoalFinished(String goal,String outcome,String message){GoalOutcomeListener callback=goalOutcomeListener;if(callback!=null&&!goal.isEmpty()){try{callback.onGoalFinished(goal,outcome,message==null?"":message);}catch(Exception e){Log.e(TAG,"Goal outcome listener failed",e);}}}
    private String getEndpoint(){return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(ENDPOINT,"").trim();}
    private String getModel(){return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(MODEL,"gpt-4o-mini").trim();}
    private String getUiSnapshot(){GestureAccessibilityService s=GestureAccessibilityService.getInstance();return s==null?"Accessibility service is not connected.":s.getUiSnapshot();}
    private void rememberAndReply(String text){String value=text==null?"":text.trim();if(value.isEmpty())return;memory.remember("assistant",value);reply(value);}
    private void status(String text){if(listener!=null)main.post(()->listener.onStatus(text));}
    private void reply(String text){if(listener!=null)main.post(()->listener.onReply(text));}
    public void shutdown(){synchronized(this){shutdown=true;generation++;queue.clear();activeGoal="";processing=false;}ai.shutdown();orchestrator.shutdown();agentExecutor.shutdownNow();}
}
