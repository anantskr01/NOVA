package com.aircontrol;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.CalendarContract;
import android.provider.Settings;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Built-in NOVA skills. Kept separate from the AI layer so new skills can be added safely. */
public final class NovaSkillRegistry {
    public interface Callback { void reply(String text); void status(String text); }
    private final Context context;
    private final Callback callback;
    private final NovaTaskManager taskManager;
    private final List<NovaSkill> extensions = new ArrayList<>();

    public NovaSkillRegistry(Context context, Callback callback, NovaTaskManager taskManager) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        this.taskManager = taskManager;
    }

    public void register(NovaSkill skill) {
        if (skill != null) extensions.add(skill);
    }

    public String describeExtensions() {
        if (extensions.isEmpty()) return "No external skills installed.";
        StringBuilder out = new StringBuilder();
        for (NovaSkill skill : extensions) {
            if (out.length() > 0) out.append(", ");
            out.append(skill.id());
        }
        return out.toString();
    }

    public boolean handle(String command) {
        String c = command.toLowerCase(Locale.ROOT).trim();
        for (NovaSkill skill : extensions) {
            try {
                if (skill.canHandle(command)) {
                    skill.handle(command, callback);
                    return true;
                }
            } catch (Exception ignored) {
                callback.status("SKILL FAILED • " + skill.id());
            }
        }
        if (isCodingGoal(c)) return startCodingAgent(command);
        if (c.equals("what is my battery") || c.contains("battery level")) {
            android.os.BatteryManager bm = (android.os.BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            int level = bm == null ? -1 : bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
            callback.reply(level >= 0 ? "Battery is at " + level + " percent." : "I couldn't read the battery level.");
            return true;
        }
        if (c.equals("device info") || c.contains("about this tablet")) {
            callback.reply("This is " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE + ".");
            return true;
        }
        if (c.equals("open calendar") || c.contains("open my calendar")) {
            Intent i = new Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i); callback.reply("Opening your calendar."); return true;
        }
        if (c.equals("open maps") || c.contains("open google maps")) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i); callback.reply("Opening maps."); return true;
        }
        if (c.startsWith("navigate to ") || c.startsWith("take me to ")) {
            String place = command.replaceFirst("(?i)^(navigate to|take me to)\\s+", "").trim();
            if (!place.isEmpty()) {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(place))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(i); callback.reply("Starting navigation to " + place + "."); return true;
            }
        }
        if (c.startsWith("calculate ") || c.startsWith("what is ") && c.matches(".*[0-9].*[+\\-*/].*[0-9].*")) {
            String expression = command.replaceFirst("(?i)^calculate\\s+", "").trim();
            if (expression.startsWith("what is ")) expression = expression.substring(8).trim();
            String result = calculate(expression);
            if (result != null) { callback.reply(expression + " = " + result); return true; }
        }
        Matcher timer = Pattern.compile("(?i)(?:set |start )?(?:a )?timer for (\\d+)\\s*(seconds?|minutes?|mins?|hours?|hrs?)").matcher(command);
        if (timer.find()) {
            long n = Long.parseLong(timer.group(1));
            String unit = timer.group(2).toLowerCase(Locale.ROOT);
            long ms = unit.startsWith("hour") || unit.startsWith("hr") ? n * 3600000L : unit.startsWith("second") ? n * 1000L : n * 60000L;
            scheduleReminder(ms, "Timer finished."); callback.reply("Timer set for " + timer.group(1) + " " + timer.group(2) + "."); return true;
        }
        Matcher reminder = Pattern.compile("(?i)remind me in (\\d+)\\s*(seconds?|minutes?|mins?|hours?|hrs?)\\s*(?:to|that)\\s+(.+)").matcher(command);
        if (reminder.find()) {
            long n = Long.parseLong(reminder.group(1));
            String unit = reminder.group(2).toLowerCase(Locale.ROOT);
            long ms = unit.startsWith("hour") || unit.startsWith("hr") ? n * 3600000L : unit.startsWith("second") ? n * 1000L : n * 60000L;
            String note = reminder.group(3).trim();
            scheduleReminder(ms, note); callback.reply("Okay. I'll remind you in " + reminder.group(1) + " " + reminder.group(2) + "."); return true;
        }
        if (c.equals("open files") || c.contains("file manager")) {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("*/*"); i.addCategory(Intent.CATEGORY_OPENABLE); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(i);
            callback.reply("Opening files."); return true;
        }
        if (c.equals("open downloads") || c.contains("downloads folder")) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("content://downloads/my_downloads")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i); callback.reply("Opening downloads."); return true;
        }
        if (c.contains("open bluetooth settings")) { context.startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); callback.reply("Opening Bluetooth settings."); return true; }
        if (c.contains("open wifi settings") || c.contains("wi-fi settings")) { context.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); callback.reply("Opening Wi-Fi settings."); return true; }
        return false;
    }

    private boolean isCodingGoal(String command) {
        return command.startsWith("code ") || command.startsWith("implement ") || command.startsWith("fix code ")
                || command.startsWith("fix the code") || command.startsWith("debug ") || command.startsWith("refactor ")
                || command.startsWith("modify the code") || command.startsWith("update the code")
                || command.startsWith("build the project") || command.startsWith("make the project")
                || command.contains("in my nova project") || command.contains("in the nova repo");
    }

    private boolean startCodingAgent(String goal) {
        NovaPcAgent pc = NovaPcRuntime.get();
        if (pc == null) {
            callback.reply("The PC coding agent is not connected. Open PC agent setup first.");
            return true;
        }
        if (taskManager == null) {
            callback.reply("The NOVA task manager is not ready.");
            return true;
        }
        android.content.SharedPreferences prefs = context.getSharedPreferences(NovaAiProviderRouter.PREFS, Context.MODE_PRIVATE);
        String endpoint = prefs.getString("endpoint", "local://nova");
        String model = prefs.getString("model", "gemini-3.8-flash");
        String apiKey = new NovaSecureStore(context).getApiKey();
        final NovaCodingAgent[] holder = new NovaCodingAgent[1];
        String taskId = taskManager.submitExternal(goal, NovaTaskManager.PRIORITY_NORMAL, new NovaTaskManager.ExternalTask() {
            @Override public void start(NovaTaskManager.ExternalListener taskListener) {
                callback.status("CODING AGENT • STARTING");
                NovaCodingAgent agent = new NovaCodingAgent(new NovaAiProviderRouter(context), endpoint, apiKey, model, pc);
                holder[0] = agent;
                agent.start(goal, new NovaCodingAgent.Listener() {
                    @Override public void onStatus(String text) { taskListener.onStatus(text); callback.status(text); }
                    @Override public void onFinished(boolean success, String summary) {
                        callback.status(success ? "CODING AGENT • VERIFIED" : "CODING AGENT • FAILED");
                        callback.reply(summary);
                        taskListener.onFinished(success, summary);
                        agent.shutdown();
                        holder[0] = null;
                    }
                });
            }
            @Override public void cancel() {
                NovaCodingAgent agent = holder[0];
                if (agent != null) agent.shutdown();
                holder[0] = null;
                callback.status("CODING AGENT • CANCELLED");
            }
        });
        if (taskId.isEmpty()) callback.reply("I couldn't add the coding task to NOVA's task manager.");
        else callback.status("TASK " + taskId + " • CODING • ACCEPTED");
        return true;
    }

    private void scheduleReminder(long delayMs, String title) {
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm == null) return;
        Intent i = new Intent(context, NovaReminderReceiver.class).putExtra("title", title);
        int request = (int) (System.currentTimeMillis() & 0x7fffffff);
        PendingIntent pi = PendingIntent.getBroadcast(context, request, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long trigger = SystemClock.elapsedRealtime() + Math.max(1000L, delayMs);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
        else alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
    }

    private String calculate(String expression) {
        try {
            String s = expression.replace("×", "*").replace("÷", "/").replaceAll("[^0-9+\\-*/(). ]", "").trim();
            if (s.isEmpty()) return null;
            return format(eval(s));
        } catch (Exception ignored) { return null; }
    }
    private double eval(String s) {
        return new Object() { int pos = -1, ch;
            void next(){ ch=++pos<s.length()?s.charAt(pos):-1; }
            boolean eat(int c){ while(ch==' ')next(); if(ch==c){next();return true;} return false; }
            double parse(){ next(); double x=parseExpression(); if(pos<s.length()) throw new RuntimeException(); return x; }
            double parseExpression(){ double x=parseTerm(); for(;;){ if(eat('+'))x+=parseTerm(); else if(eat('-'))x-=parseTerm(); else return x; } }
            double parseTerm(){ double x=parseFactor(); for(;;){ if(eat('*'))x*=parseFactor(); else if(eat('/'))x/=parseFactor(); else return x; } }
            double parseFactor(){ if(eat('+'))return parseFactor(); if(eat('-'))return -parseFactor(); int start=pos; if(eat('(')){double x=parseExpression(); if(!eat(')'))throw new RuntimeException(); return x;} while((ch>='0'&&ch<='9')||ch=='.')next(); if(start==pos)throw new RuntimeException(); return Double.parseDouble(s.substring(start,pos)); }
        }.parse();
    }
    private String format(double x) { if (Double.isNaN(x)||Double.isInfinite(x)) return null; if (Math.rint(x)==x) return Long.toString((long)x); return String.format(Locale.US,"%.6f",x).replaceAll("0+$","").replaceAll("\\.$",""); }
}
