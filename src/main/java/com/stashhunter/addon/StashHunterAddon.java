package com.stashhunter.addon;

import com.stashhunter.addon.modules.PlayerLockFly;
import com.stashhunter.addon.modules.StashHunter;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class StashHunterAddon extends MeteorAddon {
    public static final Category CATEGORY = new Category("Stash Hunter");

    @Override
    public void onInitialize() {
        Modules.get().add(new StashHunter());
        Modules.get().add(new PlayerLockFly());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.stashhunter.addon";
    }
}
