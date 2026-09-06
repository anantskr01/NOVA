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

/**
 * NOVA Food Agent.
 *
 * Phases 1-7:
 * 1) intent extraction
 * 2) launch installed delivery app
 * 3) search and inspect accessibility UI
 * 4) rank visible food/restaurant candidates
 * 5) prepare the cart
 * 6) inspect checkout and request explicit confirmation
 * 7) after explicit confirmation, submit the order and monitor the UI.
 *
 * Important: NOVA never submits a paid order merely because the original request
 * contained the word "order". The final purchase action is a separate state and
 * requires an explicit confirmation command such as "yes, place it".
 */
public final class NovaFoodAgent {
    public interface Callback {
        void status(String text);
        void reply(String text);
    }

    public static final class FoodRequest {
        public final String item;
        public final String preference;
        public final int budget;
        public final int quantity;

        FoodRequest(String item, String preference, int budget, int quantity) {
            this.item = item;
            this.preference = preference;
            this.budget = budget;
            this.quantity = quantity;
        }

        public String summary() {
            StringBuilder s = new StringBuilder();
            if (!item.isEmpty()) s.append(item);
            if (!preference.isEmpty()) s.append(" • ").append(preference);
            if (budget > 0) s.append(" • under ₹").append(budget);
            if (quantity > 1) s.append(" • qty ").append(quantity);
            return s.toString();
        }
    }

    private enum State { IDLE, SEARCHING, SELECTING, CART, CHECKOUT, WAITING_CONFIRMATION, ORDERING, TRACKING }

    private static final String ZOMATO = "com.application.zomato";
    private static final String SWIGGY = "in.swiggy.android";
    private static final long UI_DELAY_MS = 900L;
    private static final int MAX_UI_TEXT = 9000;

    private static final Pattern BUDGET = Pattern.compile("(?i)(?:under|below|less than|max(?:imum)?|upto|up to)\\s*(?:₹|rs\\.?\\s*)?(\\d{2,5})");
    private static final Pattern QUANTITY = Pattern.compile("(?i)(?:x|qty|quantity|for)\\s*(\\d{1,2})");
    private static final Pattern RUPEE = Pattern.compile("₹\\s*([0-9]{2,5})|(?:rs\\.?|inr)\\s*([0-9]{2,5})", Pattern.CASE_INSENSITIVE);

    private final Context context;
    private final Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private State state = State.IDLE;
    private FoodRequest request;
    private String activePackage = "";
    private int candidateIndex = 0;
    private int lastSeenPrice = -1;
    private String lastCandidate = "";
    private boolean orderRequested = false;
    private boolean processingEvent = false;

    public NovaFoodAgent(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    /** Handles both new food requests and confirmations for an existing checkout. */
    public synchronized boolean handle(String raw) {
        if (raw == null) return false;
        String text = raw.trim();
        if (text.isEmpty()) return false;

        if (state == State.WAITING_CONFIRMATION && isPositiveConfirmation(text)) {
            state = State.ORDERING;
            callback.status("FOOD AGENT • CONFIRMED • PLACING ORDER");
            callback.reply("Confirmed. I'll place the order now.");
            performFinalOrderClick();
            return true;
        }
        if (state == State.WAITING_CONFIRMATION && isNegativeConfirmation(text)) {
            cancel();
            callback.reply("Okay. I won't place the order.");
            return true;
        }
        if (state != State.IDLE && isStopCommand(text)) {
            cancel();
            callback.reply("Food ordering stopped.");
            return true;
        }

        FoodRequest parsed = parse(text);
        if (parsed == null) return false;

        request = parsed;
        orderRequested = contains(text.toLowerCase(Locale.ROOT), "order", "buy", "purchase", "checkout", "place it", "get it");
        state = State.SEARCHING;
        candidateIndex = 0;
        lastSeenPrice = -1;
        lastCandidate = "";
        callback.status("FOOD AGENT • " + request.summary());
        callback.reply("Got it. I'll search delivery apps, compare the visible options, prepare the best match, and ask before any paid order.");
        launchBestDeliveryApp();
        return true;
    }

    public synchronized FoodRequest parse(String raw) {
        if (raw == null) return null;
        String c = raw.trim().toLowerCase(Locale.ROOT);
        if (c.isEmpty()) return null;

        boolean foodSignal = contains(c, "food", "eat", "hungry", "order", "restaurant", "paneer", "pizza", "burger", "momos", "biryani", "noodles", "fried rice", "tikka", "sandwich", "thali", "dosa", "idli", "chowmein", "chicken", "veg", "meal");
        if (!foodSignal) return null;

        String item = extractItem(raw);
        if (item.isEmpty() && !contains(c, "hungry", "something to eat", "food")) return null;

        String preference = "";
        if (contains(c, "spicy", "hot", "masala", "fiery", "extra spicy", "very spicy")) preference = "spicy";
        else if (contains(c, "sweet")) preference = "sweet";
        else if (contains(c, "healthy", "light")) preference = "healthy";

        int budget = extractInt(BUDGET, c);
        int quantity = Math.max(1, extractInt(QUANTITY, c));
        return new FoodRequest(item, preference, budget, quantity);
    }

    private String extractItem(String raw) {
        String cleaned = raw.trim();
        cleaned = cleaned.replaceAll("(?i)\\b(hey\\s+nova|nova)\\b", " ");
        cleaned = cleaned.replaceAll("(?i)\\b(find|search|look for|get|order|buy|bring|give me|i want|i need|can you find|please|show me|pick|choose)\\b", " ");
        cleaned = cleaned.replaceAll("(?i)\\b(the|best|good|near me|nearby|around me|for me|to eat|right now|please|delivery|deliver)\\b", " ");
        cleaned = cleaned.replaceAll("(?i)(?:under|below|less than|max(?:imum)?|upto|up to)\\s*(?:₹|rs\\.?\\s*)?\\d{2,5}", " ");
        cleaned = cleaned.replaceAll("(?i)\\b(?:spicy|hot|masala|fiery|extra spicy|very spicy|sweet|healthy|light)\\b", " ");
        cleaned = cleaned.replaceAll("(?i)\\b(?:food|restaurant|something|meal)\\b", " ");
        cleaned = cleaned.replaceAll("(?i)\\b(?:x|qty|quantity|for)\\s*\\d{1,2}\\b", " ");
        cleaned = cleaned.replaceAll("(?i)\\b(?:on|from)\\s+(?:zomato|swiggy)\\b", " ");
        cleaned = cleaned.replaceAll("[,:;]+", " ");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        if (cleaned.equalsIgnoreCase("hungry") || cleaned.isEmpty()) return "";
        return cleaned;
    }

    private int extractInt(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private boolean contains(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private boolean isPositiveConfirmation(String text) {
        String c = text.toLowerCase(Locale.ROOT).trim();
        return c.matches("(?:yes|yeah|yep|ok|okay|confirm|confirmed|do it|place it|order it|go ahead|proceed|yes place it|yes order it|confirm order|place the order)")
                || c.contains("yes, place") || c.contains("confirm order") || c.contains("go ahead and order");
    }

    private boolean isNegativeConfirmation(String text) {
        String c = text.toLowerCase(Locale.ROOT).trim();
        return c.matches("(?:no|nope|cancel|don't|do not|stop|not now|skip)") || c.contains("don't order") || c.contains("do not order");
    }

    private boolean isStopCommand(String text) {
        String c = text.toLowerCase(Locale.ROOT);
        return c.equals("stop") || c.contains("cancel food") || c.contains("cancel order") || c.contains("stop ordering");
    }

    private void launchBestDeliveryApp() {
        PackageManager pm = context.getPackageManager();
        boolean zomato = isInstalled(pm, ZOMATO);
        boolean swiggy = isInstalled(pm, SWIGGY);
        String preferred = "";
        if (zomato) preferred = ZOMATO;
        else if (swiggy) preferred = SWIGGY;

        if (preferred.isEmpty()) {
            callback.status("FOOD AGENT • NO DELIVERY APP INSTALLED");
            callback.reply("I couldn't find Zomato or Swiggy installed. I'll open a web search instead.");
            openWebSearch();
            return;
        }
        activePackage = preferred;
        try {
            Intent launch = pm.getLaunchIntentForPackage(preferred);
            if (launch == null) throw new IllegalStateException("No launcher activity");
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(launch);
            callback.status("FOOD AGENT • OPENED • " + (ZOMATO.equals(preferred) ? "ZOMATO" : "SWIGGY"));
            scheduleUiStep();
        } catch (Exception e) {
            callback.status("FOOD AGENT • APP LAUNCH FAILED");
            openWebSearch();
        }
    }

    private boolean isInstalled(PackageManager pm, String packageName) {
        try { pm.getPackageInfo(packageName, 0); return true; }
        catch (Exception ignored) { return false; }
    }

    private void openWebSearch() {
        String query = request == null ? "food near me" : request.summary() + " restaurant near me";
        try {
            Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)));
            browser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(browser);
        } catch (Exception e) { callback.status("FOOD AGENT • SEARCH COULD NOT OPEN"); }
    }

    /** Called by GestureAccessibilityService for UI-driven agent progress. */
    public synchronized void onAccessibilityEvent() {
        if (state == State.IDLE || state == State.WAITING_CONFIRMATION || processingEvent) return;
        if (GestureAccessibilityService.getInstance() == null) return;
        processingEvent = true;
        handler.postDelayed(() -> {
            try { inspectAndAct(); }
            finally { processingEvent = false; }
        }, UI_DELAY_MS);
    }

    private void scheduleUiStep() {
        handler.postDelayed(() -> {
            if (state != State.IDLE && state != State.WAITING_CONFIRMATION) inspectAndAct();
        }, 1200L);
    }

    private void inspectAndAct() {
        GestureAccessibilityService service = GestureAccessibilityService.getInstance();
        if (service == null || request == null) return;
        String ui = service.getUiSnapshot();
        if (ui == null || ui.isEmpty()) return;
        if (ui.length() > MAX_UI_TEXT) ui = ui.substring(0, MAX_UI_TEXT);

        String lower = ui.toLowerCase(Locale.ROOT);
        if (state == State.SEARCHING) {
            if (looksLikeSearchScreen(lower)) {
                if (!enterSearch(service)) {
                    callback.status("FOOD AGENT • SEARCH FIELD NOT FOUND • RETRYING");
                    service.swipeDown();
                    scheduleUiStep();
                    return;
                }
                state = State.SELECTING;
                callback.status("FOOD AGENT • SEARCHING • " + request.item);
                scheduleUiStep();
                return;
            }
            if (looksLoggedOut(lower)) {
                callback.reply("The delivery app needs you to sign in. Please sign in manually; NOVA won't handle passwords or verification codes.");
                state = State.IDLE;
                return;
            }
        }

        if (state == State.SELECTING) {
            if (isCheckoutLike(lower) || isCartLike(lower)) {
                state = State.CART;
            } else if (chooseBestVisibleCandidate(service, ui)) {
                state = State.CART;
                callback.status("FOOD AGENT • BEST VISIBLE MATCH SELECTED");
                scheduleUiStep();
                return;
            } else {
                service.swipeUp();
                scheduleUiStep();
                return;
            }
        }

        if (state == State.CART) {
            if (isCheckoutLike(lower)) {
                state = State.CHECKOUT;
                scheduleUiStep();
                return;
            }
            if (clickFirst(service, Arrays.asList("add to cart", "add", "add item", "add to bag"))) {
                callback.status("FOOD AGENT • ADDED TO CART");
                if (request.quantity > 1) incrementQuantity(service, request.quantity - 1);
                scheduleUiStep();
                return;
            }
            if (clickFirst(service, Arrays.asList("view cart", "cart", "go to cart", "checkout"))) {
                scheduleUiStep();
                return;
            }
            service.swipeUp();
            scheduleUiStep();
            return;
        }

        if (state == State.CHECKOUT) {
            if (looksLoggedOut(lower)) {
                callback.reply("The app requires sign-in before checkout. Please complete sign-in yourself.");
                state = State.IDLE;
                return;
            }
            int price = extractLikelyTotal(ui);
            if (request.budget > 0 && price > request.budget) {
                callback.reply("The visible checkout total is about ₹" + price + ", which is above your ₹" + request.budget + " budget. I won't place it.");
                state = State.IDLE;
                return;
            }
            lastSeenPrice = price;
            state = State.WAITING_CONFIRMATION;
            String amount = price > 0 ? " for about ₹" + price : "";
            callback.status("FOOD AGENT • READY • CONFIRMATION REQUIRED");
            callback.reply("Checkout is ready" + amount + ". Say 'yes, place it' to submit the order, or 'cancel'.");
            return;
        }

        if (state == State.TRACKING) {
            String status = summarizeTracking(lower, ui);
            callback.status("FOOD AGENT • " + status);
            callback.reply(status);
            if (!isDelivered(lower) && !isCancelled(lower)) scheduleUiStep();
            else state = State.IDLE;
        }
    }

    private boolean looksLikeSearchScreen(String lower) {
        return lower.contains("search") || lower.contains("restaurants") || lower.contains("deliver") || lower.contains("food");
    }

    private boolean looksLoggedOut(String lower) {
        return lower.contains("log in") || lower.contains("login") || lower.contains("sign in") || lower.contains("verify mobile") || lower.contains("enter otp");
    }

    private boolean isCartLike(String lower) {
        return lower.contains("cart") || lower.contains("bag") || lower.contains("item added");
    }

    private boolean isCheckoutLike(String lower) {
        return lower.contains("checkout") || lower.contains("place order") || lower.contains("proceed to pay") || lower.contains("pay now") || lower.contains("deliver to");
    }

    private boolean enterSearch(GestureAccessibilityService service) {
        List<String> labels = Arrays.asList("search for restaurants", "search restaurants", "search for food", "search", "what are you craving");
        for (String label : labels) {
            if (service.setTextOnBestEditable(label, request.item)) return true;
        }
        // Some app builds expose a search button but not its edit field until tapped.
        for (String label : labels) {
            if (service.clickText(label)) {
                if (service.setTextOnBestEditable(label, request.item)) return true;
                if (service.setTextOnAnyEditable(request.item)) return true;
            }
        }
        return service.setTextOnAnyEditable(request.item);
    }

    private boolean chooseBestVisibleCandidate(GestureAccessibilityService service, String ui) {
        List<String> exact = new ArrayList<>();
        exact.add(request.item);
        if (!request.preference.isEmpty()) exact.add(request.preference + " " + request.item);
        for (String candidate : exact) {
            if (service.clickText(candidate)) {
                lastCandidate = candidate;
                return true;
            }
        }

        // Prefer the requested preference and cheaper visible prices. Accessibility text is
        // the only reliable source available to this app; we never invent restaurant data.
        String[] lines = ui.split("\\n");
        List<Candidate> candidates = new ArrayList<>();
        for (String line : lines) {
            String l = line.trim();
            String low = l.toLowerCase(Locale.ROOT);
            if (low.length() < 3) continue;
            if (!contains(low, "paneer", "pizza", "burger", "momo", "biryani", "noodle", "tikka", "sandwich", "thali", "dosa", "chowmein", "meal")) continue;
            int price = extractPrice(l);
            int score = 0;
            if (low.contains(request.item.toLowerCase(Locale.ROOT))) score += 100;
            if (!request.preference.isEmpty() && low.contains(request.preference)) score += 50;
            if (price > 0) score += Math.max(0, 40 - Math.min(price, 40));
            if (request.budget > 0 && price > request.budget) score -= 1000;
            candidates.add(new Candidate(l, score, price));
        }
        Collections.sort(candidates, Comparator.comparingInt((Candidate c) -> c.score).reversed());
        if (!candidates.isEmpty()) {
            Candidate best = candidates.get(0);
            if (service.clickText(best.label)) {
                lastCandidate = best.label;
                return true;
            }
        }
        candidateIndex++;
        return false;
    }

    private void incrementQuantity(GestureAccessibilityService service, int count) {
        for (int i = 0; i < Math.min(count, 9); i++) {
            if (!clickFirst(service, Arrays.asList("+", "increase quantity", "add one", "plus"))) break;
        }
    }

    private boolean clickFirst(GestureAccessibilityService service, List<String> labels) {
        for (String label : labels) if (service.clickText(label)) return true;
        return false;
    }

    private void performFinalOrderClick() {
        GestureAccessibilityService service = GestureAccessibilityService.getInstance();
        if (service == null) { state = State.IDLE; callback.reply("Accessibility control is unavailable, so I stopped safely."); return; }
        // Only invoked after explicit confirmation.
        if (!clickFirst(service, Arrays.asList("place order", "place your order", "confirm order", "pay now", "proceed to pay"))) {
            callback.reply("I couldn't find the final order button. I stopped without submitting anything.");
            state = State.IDLE;
            return;
        }
        state = State.TRACKING;
        callback.status("FOOD AGENT • ORDER SUBMITTED • TRACKING");
        scheduleUiStep();
    }

    private int extractLikelyTotal(String ui) {
        String lower = ui.toLowerCase(Locale.ROOT);
        int best = -1;
        for (String line : ui.split("\\n")) {
            String l = line.toLowerCase(Locale.ROOT);
            if (l.contains("total") || l.contains("to pay") || l.contains("grand total") || l.contains("amount")) {
                int p = extractPrice(line);
                if (p > 0) best = p;
            }
        }
        if (best > 0) return best;
        Matcher m = RUPEE.matcher(ui);
        return m.find() ? firstGroup(m) : -1;
    }

    private int extractPrice(String text) {
        Matcher m = RUPEE.matcher(text);
        return m.find() ? firstGroup(m) : -1;
    }

    private int firstGroup(Matcher m) {
        String a = m.group(1), b = m.group(2);
        return Integer.parseInt(a != null ? a : b);
    }

    private String summarizeTracking(String lower, String ui) {
        if (isDelivered(lower)) return "ORDER DELIVERED";
        if (isCancelled(lower)) return "ORDER CANCELLED";
        if (lower.contains("out for delivery")) return "ORDER OUT FOR DELIVERY";
        if (lower.contains("picked up") || lower.contains("pickedup")) return "ORDER PICKED UP";
        if (lower.contains("preparing") || lower.contains("being prepared")) return "ORDER IS BEING PREPARED";
        if (lower.contains("confirmed")) return "ORDER CONFIRMED";
        return "ORDER IS BEING TRACKED";
    }

    private boolean isDelivered(String lower) { return lower.contains("delivered") || lower.contains("delivery completed"); }
    private boolean isCancelled(String lower) { return lower.contains("cancelled") || lower.contains("canceled"); }

    public synchronized void cancel() {
        state = State.IDLE;
        request = null;
        orderRequested = false;
        lastCandidate = "";
        lastSeenPrice = -1;
        handler.removeCallbacksAndMessages(null);
    }

    private static final class Candidate {
        final String label; final int score; final int price;
        Candidate(String label, int score, int price) { this.label = label; this.score = score; this.price = price; }
    }
}
