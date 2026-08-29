package com.aircontrol;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent task state for NOVA's bounded autonomous agent. */
public final class NovaTaskStore {
    public enum State { NOT_STARTED, WORKING, VERIFIED, COMPLETE, FAILED }
    private static final String PREFS="nova_task_state",GOAL="goal",ITERATION="iteration",RETRIES="retries",CURRENT_STEP="current_step",COMPLETED_STEPS="completed_steps",FAILED_STEPS="failed_steps",REMAINING_GOAL="remaining_goal",LAST_RESULT="last_result",STEP_PROGRESS="step_progress",HISTORY="history",RUNNING="running",COMPLETED="completed",STATE="state",STARTED_AT="started_at",FINISHED_AT="finished_at";
    private static final int MAX_HISTORY_CHARS=12000,MAX_STEP_PROGRESS_CHARS=6000,MAX_GOAL_CHARS=4000;
    private final SharedPreferences prefs;
    public NovaTaskStore(Context context){prefs=context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    public synchronized void begin(String goal){long now=System.currentTimeMillis();prefs.edit().putString(GOAL,trim(goal,MAX_GOAL_CHARS)).putInt(ITERATION,0).putInt(RETRIES,0).putInt(CURRENT_STEP,0).putString(COMPLETED_STEPS,"").putString(FAILED_STEPS,"").putString(REMAINING_GOAL,trim(goal,MAX_GOAL_CHARS)).putString(LAST_RESULT,"").putString(STEP_PROGRESS,"").putString(HISTORY,"").putBoolean(RUNNING,true).putBoolean(COMPLETED,false).putString(STATE,State.WORKING.name()).putLong(STARTED_AT,now).remove(FINISHED_AT).apply();}
    public synchronized void setIteration(int v){prefs.edit().putInt(ITERATION,Math.max(0,v)).apply();} public synchronized int getIteration(){return prefs.getInt(ITERATION,0);}
    public synchronized int retries(){return prefs.getInt(RETRIES,0);} public synchronized int incrementRetries(){int n=retries()+1;prefs.edit().putInt(RETRIES,n).apply();return n;} public synchronized void resetRetries(){prefs.edit().putInt(RETRIES,0).apply();}
    public synchronized String getGoal(){return prefs.getString(GOAL,"");}
    public synchronized void setCurrentStep(int v){prefs.edit().putInt(CURRENT_STEP,Math.max(0,v)).apply();} public synchronized int currentStep(){return prefs.getInt(CURRENT_STEP,0);}
    public synchronized void setCompletedSteps(String v){prefs.edit().putString(COMPLETED_STEPS,trim(v,MAX_STEP_PROGRESS_CHARS)).apply();} public synchronized String completedSteps(){return prefs.getString(COMPLETED_STEPS,"");}
    public synchronized void setFailedSteps(String v){prefs.edit().putString(FAILED_STEPS,trim(v,MAX_STEP_PROGRESS_CHARS)).apply();} public synchronized String failedSteps(){return prefs.getString(FAILED_STEPS,"");}
    public synchronized void setRemainingGoal(String v){prefs.edit().putString(REMAINING_GOAL,trim(v,MAX_GOAL_CHARS)).apply();} public synchronized String remainingGoal(){return prefs.getString(REMAINING_GOAL,"");}
    public synchronized void setLastResult(String v){prefs.edit().putString(LAST_RESULT,trim(v,MAX_STEP_PROGRESS_CHARS)).apply();} public synchronized String lastResult(){return prefs.getString(LAST_RESULT,"");}
    public synchronized void setStepProgress(String v){prefs.edit().putString(STEP_PROGRESS,trim(v,MAX_STEP_PROGRESS_CHARS)).apply();} public synchronized String stepProgress(){return prefs.getString(STEP_PROGRESS,"");}
    public synchronized void appendHistory(String entry){if(entry==null||entry.trim().isEmpty())return;String c=prefs.getString(HISTORY,"");String n=(c==null||c.isEmpty())?entry.trim():c+"\n"+entry.trim();prefs.edit().putString(HISTORY,trimTail(n,MAX_HISTORY_CHARS)).apply();}
    public synchronized String history(){return prefs.getString(HISTORY,"");} public synchronized boolean isRunning(){return prefs.getBoolean(RUNNING,false);} public synchronized boolean isCompleted(){return prefs.getBoolean(COMPLETED,false);}
    public synchronized State state(){try{return State.valueOf(prefs.getString(STATE,State.NOT_STARTED.name()));}catch(Exception ignored){return State.NOT_STARTED;}}
    public synchronized void setState(State state){prefs.edit().putString(STATE,(state==null?State.NOT_STARTED:state).name()).apply();}
    public synchronized long startedAt(){return prefs.getLong(STARTED_AT,0L);} public synchronized long finishedAt(){return prefs.getLong(FINISHED_AT,0L);}
    public synchronized void markVerified(){setState(State.VERIFIED);resetRetries();} public synchronized void finish(){finish(false);}
    public synchronized void finish(boolean completed){prefs.edit().putBoolean(RUNNING,false).putBoolean(COMPLETED,completed).putString(STATE,(completed?State.COMPLETE:State.FAILED).name()).putLong(FINISHED_AT,System.currentTimeMillis()).apply();}
    public synchronized void clear(){prefs.edit().clear().apply();}
    private static String trim(String v,int max){if(v==null)return "";String s=v.trim();return s.length()>max?s.substring(0,max):s;} private static String trimTail(String v,int max){return v==null?"":v.length()>max?v.substring(v.length()-max):v;}
}
