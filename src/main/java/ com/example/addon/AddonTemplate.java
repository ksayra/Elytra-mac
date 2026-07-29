package com.example.addon;

import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Modules;
import com.example.addon.modules.ElytraMaceAuto;

public class AddonTemplate extends MeteorAddon {
    @Override
    public void onInitialize() {
        Modules.get().add(new ElytraMaceAuto());
    }

    @Override
    public String getPackage() {
        return "com.example.addon";
    }
}
