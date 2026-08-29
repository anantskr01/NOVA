package com.aircontrol;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;

/** Central, permission-aware action layer used by voice, text and AI plans. */
public final class NovaActionEngine {
    public interface Callback { void status(String text); void reply(String text); }
    private final Context context; private final Callback callback; private final NovaAppCatalog apps;
    public NovaActionEngine(Context context,Callback callback){this.context=context.getApplicationContext();this.callback=callback;this.apps=new NovaAppCatalog(this.context);}
    public boolean execute(String type,String value){
        try{switch(type==null?"none":type.trim().toLowerCase(java.util.Locale.ROOT)){
            case "home":return global(AccessibilityService.GLOBAL_ACTION_HOME); case "back":return global(AccessibilityService.GLOBAL_ACTION_BACK); case "recents":return global(AccessibilityService.GLOBAL_ACTION_RECENTS);
            case "notifications":return global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS); case "quick_settings":return global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS);
            case "scroll_up":return swipe("up"); case "scroll_down":return swipe("down"); case "swipe_left":return swipe("left"); case "swipe_right":return swipe("right");
            case "open_url":if(value==null||value.trim().isEmpty())return false;launch(new Intent(Intent.ACTION_VIEW,Uri.parse(value.trim())));return true;
            case "open_package":if(value==null||value.trim().isEmpty())return false;Intent pkg=context.getPackageManager().getLaunchIntentForPackage(value.trim());if(pkg==null)return false;launch(pkg);return true;
            case "open_app":NovaAppCatalog catalog=apps;android.content.pm.ResolveInfo info=catalog.resolve(value);Intent appIntent=catalog.launchIntent(info);if(appIntent==null)return false;launch(appIntent);return true;
            case "settings":launch(new Intent(Settings.ACTION_SETTINGS));return true;
            case "none":default:return false;}}
        catch(Exception e){callback.status("ACTION FAILED");return false;}
    }
    public boolean global(int action){GestureAccessibilityService service=GestureAccessibilityService.getInstance();if(service==null){callback.status("ACCESSIBILITY NOT CONNECTED");return false;}return service.performGlobalActionPublic(action);}
    private boolean swipe(String direction){GestureAccessibilityService service=GestureAccessibilityService.getInstance();if(service==null){callback.status("ACCESSIBILITY NOT CONNECTED");return false;}if("up".equals(direction))service.swipeUp();else if("down".equals(direction))service.swipeDown();else if("left".equals(direction))service.swipeLeft();else service.swipeRight();return true;}
    private void launch(Intent intent){intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);context.startActivity(intent);}
}
