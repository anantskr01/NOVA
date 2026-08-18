package com.aircontrol;

/** Extension point for adding isolated NOVA capabilities without changing the AI core. */
public interface NovaSkill {
    String id();
    boolean canHandle(String command);
    void handle(String command, NovaSkillRegistry.Callback callback);
}
