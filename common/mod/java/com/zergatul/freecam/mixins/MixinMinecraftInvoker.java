package com.zergatul.freecam.mixins;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Minecraft.class)
public interface MixinMinecraftInvoker {

    @Invoker("startAttack")
    boolean freecam$startAttack();

    @Invoker("startUseItem")
    void freecam$startUseItem();
}
