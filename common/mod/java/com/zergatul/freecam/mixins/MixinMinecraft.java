package com.zergatul.freecam.mixins;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.zergatul.freecam.FreeCam;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MixinMinecraft {

    @Inject(at = @At("HEAD"), method = "clearClientLevel")
    private void onClearClientLevel(Screen screen, CallbackInfo ci) {
        FreeCam.instance.onWorldUnload();
    }

    @Inject(at = @At("HEAD"), method = "setLevel")
    private void onSetLevel(ClientLevel level, ReceivingLevelScreen.Reason reason, CallbackInfo ci) {
        FreeCam.instance.onWorldUnload();
    }

    @Inject(at = @At("TAIL"), method = "handleKeybinds")
    private void onHandleKeyBindings(CallbackInfo info) {
        FreeCam.instance.onHandleKeyBindings();
    }

    @ModifyExpressionValue(
            method = "handleKeybinds",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;isMouseGrabbed()Z"))
    private boolean freecam$onMouseGrabbedCheckForAttack(boolean original) {
        if (FreeCam.instance.shouldBypassMouseGrabForAttack()) {
            return true;
        }
        return original;
    }
}