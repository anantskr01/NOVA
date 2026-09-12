package com.aircontrol;

import java.util.Locale;

/** Deterministic front-door classifier. Keeps normal conversation out of the heavy agent pipeline. */
public final class NovaRequestRouter {
    public enum Route { CHAT, AGENT_TASK }

    public Route route(String request) {
        String q = request == null ? "" : request.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return Route.CHAT;

        String[] agentStarts = {
                "open ", "launch ", "close ", "start ", "send ", "text ", "call ",
                "tap ", "click ", "swipe ", "scroll ", "go home", "go back", "recent apps",
                "notifications", "quick settings", "open settings", "settings", "turn on ",
                "turn off ", "enable ", "disable ", "set ", "change ", "play ", "pause ",
                "download ", "install ", "book ", "order ", "buy ", "schedule ",
                "take a screenshot", "take screenshot", "read screen", "search for ",
                "google ", "find and open ", "then ", "and then "
        };
        for (String s : agentStarts) if (q.startsWith(s)) return Route.AGENT_TASK;

        String[] agentPhrases = {
                "open youtube and", "open chrome and", "on my tablet", "on my phone",
                "on the screen", "for me", "do this", "do that", "make it", "turn my",
                "find a restaurant", "place an order", "book a", "search and open",
                "and search", "and click", "and tap", "and send"
        };
        for (String s : agentPhrases) if (q.contains(s)) return Route.AGENT_TASK;

        return Route.CHAT;
    }
}
