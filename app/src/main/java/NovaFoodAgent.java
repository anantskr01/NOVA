package com.aircontrol;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NOVA Food Agent - phase 1.
 * Parses natural food-order requests and starts the restaurant-search flow.
 * Payment/order submission is intentionally not performed here.
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

    private final Context context;
    private final Callback callback;
    private static final Pattern BUDGET = Pattern.compile("(?i)(?:under|below|less than|max(?:imum)?|upto|up to)\\s*(?:₹|rs\\.?\\s*)?(\\d{2,5})");
    private static final Pattern QUANTITY = Pattern.compile("(?i)(?:x|qty|quantity|for)\\s*(\\d{1,2})");

    public NovaFoodAgent(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    /** Returns true when the command is clearly a food-search/order request. */
    public boolean handle(String raw) {
        FoodRequest request = parse(raw);
        if (request == null) return false;

        callback.status("FOOD AGENT • " + request.summary());
        callback.reply("I found your food request. I'll open restaurant search and prepare the best match. I will ask before placing any paid order.");
        openOrderingApp(request);
        return true;
    }

    public FoodRequest parse(String raw) {
        if (raw == null) return null;
        String c = raw.trim().toLowerCase(Locale.ROOT);
        if (c.isEmpty()) return null;

        boolean foodSignal = contains(c, "food", "eat", "hungry", "order", "restaurant", "paneer", "pizza", "burger", "momos", "biryani", "noodles", "fried rice", "tikka", "sandwich", "thali", "dosa", "idli", "chowmein", "chicken", "veg");
        if (!foodSignal) return null;

        String item = extractItem(raw, c);
        if (item.isEmpty() && !contains(c, "hungry", "something to eat", "food")) return null;

        String preference = "";
        if (contains(c, "spicy", "hot", "masala", "fiery")) preference = "spicy";
        else if (contains(c, "sweet")) preference = "sweet";
        else if (contains(c, "healthy", "light")) preference = "healthy";

        int budget = extractInt(BUDGET, c);
        int quantity = Math.max(1, extractInt(QUANTITY, c));
        return new FoodRequest(item, preference, budget, quantity);
    }

    private String extractItem(String raw, String c) {
        String cleaned = raw.trim();
        cleaned = cleaned.replaceAll("(?i)\\b(hey\\s+nova|nova)\\b", "");
        cleaned = cleaned.replaceAll("(?i)\\b(find|search|look for|get|order|buy|bring|give me|i want|i need|can you find|please)\\b", " ");
        cleaned = cleaned.replaceAll("(?i)\\b(the|best|good|near me|nearby|around me|for me|to eat|right now|please)\\b", " ");
        cleaned = cleaned.replaceAll("(?i)(?:under|below|less than|max(?:imum)?|upto|up to)\\s*(?:₹|rs\\.?\\s*)?\\d{2,5}", " ");
        cleaned = cleaned.replaceAll("(?i)\\b(?:spicy|hot|masala|fiery|sweet|healthy|light)\\b", " ");
        cleaned = cleaned.replaceAll("(?i)\\b(?:food|restaurant|something)\\b", " ");
        cleaned = cleaned.replaceAll("(?i)\\b(?:x|qty|quantity|for)\\s*\\d{1,2}\\b", " ");
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

    private void openOrderingApp(FoodRequest request) {
        // Zomato/Swiggy app navigation will be made accessibility-driven in phase 2.
        // For phase 1 we open a web search with the exact food query as a safe starting point.
        String query = request.summary();
        if (query.isEmpty()) query = "food near me";
        Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query + " restaurant near me")));
        browser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(browser);
        } catch (Exception e) {
            callback.status("FOOD AGENT • SEARCH COULD NOT OPEN");
        }
    }
}
