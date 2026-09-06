package com.aircontrol;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** End-to-end food agent with deterministic UI automation and explicit purchase confirmation. */
public final class NovaFoodAgent {
    public interface Callback { void status(String text); void reply(String text); }
    public static final class FoodRequest {
        public final String item, preference; public final int budget, quantity;
        FoodRequest(String item, String preference, int budget, int quantity) { this.item=item; this.preference=preference; this.budget=budget; this.quantity=quantity; }
        public String summary() { StringBuilder s=new StringBuilder(); if(!item.isEmpty())s.append(item); if(!preference.isEmpty())s.append(" • ").append(preference); if(budget>0)s.append(" • under ₹").append(budget); if(quantity>1)s.append(" • qty ").append(quantity); return s.toString(); }
    }
    private enum State { IDLE, SEARCHING, SELECTING, CART, CHECKOUT, WAITING_CONFIRMATION, ORDERING, TRACKING }
    private static final String ZOMATO="com.application.zomato", SWIGGY="in.swiggy.android";
    private static final long UI_DELAY_MS=900L;
    private static final Pattern BUDGET=Pattern.compile("(?i)(?:under|below|less than|max(?:imum)?|upto|up to)\\s*(?:₹|rs\\.?\\s*)?(\\d{2,5})");
    private static final Pattern QUANTITY=Pattern.compile("(?i)(?:x|qty|quantity|for)\\s*(\\d{1,2})");
    private static final Pattern RUPEE=Pattern.compile("₹\\s*([0-9]{2,5})|(?:rs\\.?|inr)\\s*([0-9]{2,5})",Pattern.CASE_INSENSITIVE);
    private static volatile NovaFoodAgent active;
    private final Context context; private final Callback callback; private final Handler handler=new Handler(Looper.getMainLooper());
    private State state=State.IDLE; private FoodRequest request; private String activePackage=""; private int lastSeenPrice=-1; private String lastCandidate=""; private boolean processingEvent;

    public NovaFoodAgent(Context context, Callback callback){this.context=context.getApplicationContext();this.callback=callback;active=this;}
    public static NovaFoodAgent getActive(){return active;}

    public synchronized boolean handle(String raw){
        if(raw==null)return false; String text=raw.trim(); if(text.isEmpty())return false;
        if(state==State.WAITING_CONFIRMATION&&isPositiveConfirmation(text)){state=State.ORDERING;callback.status("FOOD AGENT • CONFIRMED • PLACING ORDER");callback.reply("Confirmed. I'll place the order now.");performFinalOrderClick();return true;}
        if(state==State.WAITING_CONFIRMATION&&isNegativeConfirmation(text)){cancel();callback.reply("Okay. I won't place the order.");return true;}
        if(state!=State.IDLE&&isStopCommand(text)){cancel();callback.reply("Food ordering stopped.");return true;}
        FoodRequest parsed=parse(text); if(parsed==null)return false;
        request=parsed; state=State.SEARCHING; lastSeenPrice=-1; lastCandidate="";
        callback.status("FOOD AGENT • "+request.summary());
        callback.reply("Got it. I'll search delivery apps, compare the visible options, prepare the best match, and ask before any paid order.");
        launchBestDeliveryApp(); return true;
    }

    public synchronized FoodRequest parse(String raw){
        if(raw==null)return null; String c=raw.trim().toLowerCase(Locale.ROOT); if(c.isEmpty())return null;
        if(!contains(c,"food","eat","hungry","order","restaurant","paneer","pizza","burger","momos","biryani","noodles","fried rice","tikka","sandwich","thali","dosa","idli","chowmein","chicken","veg","meal"))return null;
        String item=extractItem(raw); if(item.isEmpty()&&!contains(c,"hungry","something to eat","food"))return null;
        String preference=""; if(contains(c,"spicy","hot","masala","fiery","extra spicy","very spicy"))preference="spicy"; else if(contains(c,"sweet"))preference="sweet"; else if(contains(c,"healthy","light"))preference="healthy";
        return new FoodRequest(item,preference,extractInt(BUDGET,c),Math.max(1,extractInt(QUANTITY,c)));
    }

    private String extractItem(String raw){
        String x=raw.trim();
        x=x.replaceAll("(?i)\\b(hey\\s+nova|nova)\\b"," ");
        x=x.replaceAll("(?i)\\b(find|search|look for|get|order|buy|bring|give me|i want|i need|can you find|please|show me|pick|choose)\\b"," ");
        x=x.replaceAll("(?i)\\b(the|best|good|near me|nearby|around me|for me|to eat|right now|delivery|deliver)\\b"," ");
        x=x.replaceAll("(?i)(?:under|below|less than|max(?:imum)?|upto|up to)\\s*(?:₹|rs\\.?\\s*)?\\d{2,5}"," ");
        x=x.replaceAll("(?i)\\b(?:spicy|hot|masala|fiery|extra spicy|very spicy|sweet|healthy|light)\\b"," ");
        x=x.replaceAll("(?i)\\b(?:food|restaurant|something|meal)\\b"," ");
        x=x.replaceAll("(?i)\\b(?:x|qty|quantity|for)\\s*\\d{1,2}\\b"," ");
        x=x.replaceAll("(?i)\\b(?:on|from)\\s+(?:zomato|swiggy)\\b"," ");
        x=x.replaceAll("[,:;]+"," ").replaceAll("\\s+"," ").trim(); return x.equalsIgnoreCase("hungry")?"":x;
    }
    private int extractInt(Pattern p,String text){Matcher m=p.matcher(text);return m.find()?Integer.parseInt(m.group(1)):0;}
    private boolean contains(String v,String... terms){for(String t:terms)if(v.contains(t))return true;return false;}
    private boolean isPositiveConfirmation(String text){String c=text.toLowerCase(Locale.ROOT).trim();return c.matches("(?:yes|yeah|yep|ok|okay|confirm|confirmed|do it|place it|order it|go ahead|yes place it|yes order it|confirm order|place the order)")||c.contains("yes, place")||c.contains("confirm order")||c.contains("go ahead and order");}
    private boolean isNegativeConfirmation(String text){String c=text.toLowerCase(Locale.ROOT).trim();return c.matches("(?:no|nope|cancel|don't|do not|not now|skip)")||c.contains("don't order")||c.contains("do not order");}
    private boolean isStopCommand(String text){String c=text.toLowerCase(Locale.ROOT);return c.equals("stop")||c.contains("cancel food")||c.contains("cancel order")||c.contains("stop ordering");}

    private void launchBestDeliveryApp(){
        PackageManager pm=context.getPackageManager(); boolean z=isInstalled(pm,ZOMATO),s=isInstalled(pm,SWIGGY); String pkg=z?ZOMATO:(s?SWIGGY:"");
        if(pkg.isEmpty()){callback.reply("I couldn't find Zomato or Swiggy installed. I'll open a web search instead.");openWebSearch();return;}
        activePackage=pkg; try{Intent i=pm.getLaunchIntentForPackage(pkg);if(i==null)throw new IllegalStateException();i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);context.startActivity(i);callback.status("FOOD AGENT • OPENED • "+(z?"ZOMATO":"SWIGGY"));scheduleUiStep();}catch(Exception e){callback.status("FOOD AGENT • APP LAUNCH FAILED");openWebSearch();}
    }
    private boolean isInstalled(PackageManager pm,String pkg){try{pm.getPackageInfo(pkg,0);return true;}catch(Exception e){return false;}}
    private void openWebSearch(){try{String q=request==null?"food near me":request.summary()+" restaurant near me";Intent i=new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.google.com/search?q="+Uri.encode(q)));i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);context.startActivity(i);}catch(Exception ignored){callback.status("FOOD AGENT • SEARCH COULD NOT OPEN");}}

    public synchronized void onAccessibilityEvent(){if(state==State.IDLE||state==State.WAITING_CONFIRMATION||processingEvent)return;if(GestureAccessibilityService.getInstance()==null)return;processingEvent=true;handler.postDelayed(()->{try{inspectAndAct();}finally{processingEvent=false;}},UI_DELAY_MS);}
    private void scheduleUiStep(){handler.postDelayed(()->{if(state!=State.IDLE&&state!=State.WAITING_CONFIRMATION)inspectAndAct();},1200L);}

    private void inspectAndAct(){
        GestureAccessibilityService svc=GestureAccessibilityService.getInstance();if(svc==null||request==null)return;String ui=svc.getUiSnapshot();if(ui==null||ui.isEmpty())return;String lower=ui.toLowerCase(Locale.ROOT);
        if(state==State.SEARCHING){
            if(looksLoggedOut(lower)){callback.reply("The delivery app needs you to sign in. Please sign in manually; NOVA won't handle passwords or verification codes.");state=State.IDLE;return;}
            if(enterSearch(svc)){state=State.SELECTING;callback.status("FOOD AGENT • SEARCHING • "+request.item);scheduleUiStep();return;}
            scheduleUiStep();return;
        }
        if(state==State.SELECTING){
            if(isCheckoutLike(lower)||isCartLike(lower)){state=State.CART;scheduleUiStep();return;}
            if(chooseBestVisibleCandidate(svc,ui)){state=State.CART;callback.status("FOOD AGENT • BEST VISIBLE MATCH SELECTED");scheduleUiStep();return;}
            svc.swipeUp();scheduleUiStep();return;
        }
        if(state==State.CART){
            if(isCheckoutLike(lower)){state=State.CHECKOUT;scheduleUiStep();return;}
            if(clickFirst(svc,Arrays.asList("add to cart","add item","add to bag"))){callback.status("FOOD AGENT • ADDED TO CART");incrementQuantity(svc,request.quantity-1);scheduleUiStep();return;}
            if(clickFirst(svc,Arrays.asList("view cart","cart","go to cart","checkout"))){scheduleUiStep();return;}
            svc.swipeUp();scheduleUiStep();return;
        }
        if(state==State.CHECKOUT){
            if(looksLoggedOut(lower)){callback.reply("The app requires sign-in before checkout. Please complete sign-in yourself.");state=State.IDLE;return;}
            int price=extractLikelyTotal(ui);if(request.budget>0&&price>request.budget){callback.reply("The visible checkout total is about ₹"+price+", above your ₹"+request.budget+" budget. I won't place it.");state=State.IDLE;return;}
            lastSeenPrice=price;state=State.WAITING_CONFIRMATION;callback.status("FOOD AGENT • READY • CONFIRMATION REQUIRED");callback.reply("Checkout is ready"+(price>0?" for about ₹"+price:"")+". Say 'yes, place it' to submit the order, or 'cancel'.");return;
        }
        if(state==State.TRACKING){String s=summarizeTracking(lower);callback.status("FOOD AGENT • "+s);callback.reply(s);if(isDelivered(lower)||isCancelled(lower))state=State.IDLE;else scheduleUiStep();}
    }
    private boolean looksLoggedOut(String l){return l.contains("log in")||l.contains("login")||l.contains("sign in")||l.contains("verify mobile")||l.contains("enter otp");}
    private boolean isCartLike(String l){return l.contains("cart")||l.contains("bag")||l.contains("item added");}
    private boolean isCheckoutLike(String l){return l.contains("checkout")||l.contains("place order")||l.contains("proceed to pay")||l.contains("pay now")||l.contains("deliver to");}

    private boolean enterSearch(GestureAccessibilityService svc){
        for(String label:Arrays.asList("search for restaurants","search restaurants","search for food","what are you craving","search")){
            if(svc.setTextOnBestEditable(label,request.item)||svc.clickText(label)&&svc.setTextOnAnyEditable(request.item))return true;
        }
        return svc.setTextOnAnyEditable(request.item);
    }
    private boolean chooseBestVisibleCandidate(GestureAccessibilityService svc,String ui){
        for(String x:Arrays.asList(request.item,request.preference.isEmpty()?"":request.preference+" "+request.item))if(!x.isEmpty()&&svc.clickText(x)){lastCandidate=x;return true;}
        List<Candidate> cs=new ArrayList<>();for(String line:ui.split("\\n")){String l=line.trim(),low=l.toLowerCase(Locale.ROOT);if(l.length()<3||!contains(low,"paneer","pizza","burger","momo","biryani","noodle","tikka","sandwich","thali","dosa","chowmein","meal"))continue;int p=extractPrice(l),score=0;if(low.contains(request.item.toLowerCase(Locale.ROOT)))score+=100;if(!request.preference.isEmpty()&&low.contains(request.preference))score+=50;if(p>0)score+=Math.max(0,40-Math.min(p,40));if(request.budget>0&&p>request.budget)score-=1000;cs.add(new Candidate(l,score));}
        Collections.sort(cs,Comparator.comparingInt((Candidate c)->c.score).reversed());if(!cs.isEmpty()&&svc.clickText(cs.get(0).label)){lastCandidate=cs.get(0).label;return true;}return false;
    }
    private void incrementQuantity(GestureAccessibilityService svc,int n){for(int i=0;i<Math.min(n,9);i++)if(!clickFirst(svc,Arrays.asList("+","increase quantity","add one","plus")))break;}
    private boolean clickFirst(GestureAccessibilityService svc,List<String> labels){for(String x:labels)if(svc.clickText(x))return true;return false;}

    private void performFinalOrderClick(){
        GestureAccessibilityService svc=GestureAccessibilityService.getInstance();if(svc==null){state=State.IDLE;callback.reply("Accessibility control is unavailable, so I stopped safely.");return;}
        if(!clickFirst(svc,Arrays.asList("place order","place your order","confirm order","pay now","proceed to pay"))){callback.reply("I couldn't find the final order button. I stopped without submitting anything.");state=State.IDLE;return;}
        state=State.TRACKING;callback.status("FOOD AGENT • ORDER SUBMITTED • TRACKING");scheduleUiStep();
    }
    private int extractLikelyTotal(String ui){for(String line:ui.split("\\n")){String l=line.toLowerCase(Locale.ROOT);if(l.contains("total")||l.contains("to pay")||l.contains("grand total")||l.contains("amount")){int p=extractPrice(line);if(p>0)return p;}}Matcher m=RUPEE.matcher(ui);return m.find()?firstGroup(m):-1;}
    private int extractPrice(String text){Matcher m=RUPEE.matcher(text);return m.find()?firstGroup(m):-1;}
    private int firstGroup(Matcher m){return Integer.parseInt(m.group(1)!=null?m.group(1):m.group(2));}
    private String summarizeTracking(String l){if(isDelivered(l))return"ORDER DELIVERED";if(isCancelled(l))return"ORDER CANCELLED";if(l.contains("out for delivery"))return"ORDER OUT FOR DELIVERY";if(l.contains("picked up")||l.contains("pickedup"))return"ORDER PICKED UP";if(l.contains("preparing")||l.contains("being prepared"))return"ORDER IS BEING PREPARED";if(l.contains("confirmed"))return"ORDER CONFIRMED";return"ORDER IS BEING TRACKED";}
    private boolean isDelivered(String l){return l.contains("delivered")||l.contains("delivery completed");}
    private boolean isCancelled(String l){return l.contains("cancelled")||l.contains("canceled");}
    public synchronized void cancel(){state=State.IDLE;request=null;lastCandidate="";lastSeenPrice=-1;handler.removeCallbacksAndMessages(null);}
    private static final class Candidate{final String label;final int score;Candidate(String l,int s){label=l;score=s;}}
}
