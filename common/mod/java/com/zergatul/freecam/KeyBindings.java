package com.zergatul.freecam;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public class KeyBindings {
    public static final KeyMapping toggleFreeCam = new KeyMapping("key.zergatul.freecam.toggle", GLFW.GLFW_KEY_F6, "category.zergatul.freecam");
    public static final KeyMapping toggleCameraLock = new KeyMapping("key.zergatul.freecam.cameralock.toggle", InputConstants.UNKNOWN.getValue(), "category.zergatul.freecam");
    public static final KeyMapping toggleEyeLock = new KeyMapping("key.zergatul.freecam.eyelock.toggle", InputConstants.UNKNOWN.getValue(), "category.zergatul.freecam");
    public static final KeyMapping toggleFollowCam = new KeyMapping("key.zergatul.freecam.followcam.toggle", InputConstants.UNKNOWN.getValue(), "category.zergatul.freecam");
    public static final KeyMapping startPath = new KeyMapping("key.zergatul.freecam.start.path", InputConstants.UNKNOWN.getValue(), "category.zergatul.freecam");

    public static final KeyMapping forceSelect = new KeyMapping("key.zergatul.freecam.forceselect", GLFW.GLFW_KEY_LEFT_CONTROL, "category.zergatul.freecam");
    public static final KeyMapping rotateLeft = new KeyMapping("key.zergatul.freecam.rotateleft", GLFW.GLFW_KEY_LEFT, "category.zergatul.freecam");
    public static final KeyMapping rotateRight = new KeyMapping("key.zergatul.freecam.rotateright", GLFW.GLFW_KEY_RIGHT, "category.zergatul.freecam");
    public static final KeyMapping rotateUp = new KeyMapping("key.zergatul.freecam.rotateup", GLFW.GLFW_KEY_UP, "category.zergatul.freecam");
    public static final KeyMapping rotateDown = new KeyMapping("key.zergatul.freecam.rotatedown", GLFW.GLFW_KEY_DOWN, "category.zergatul.freecam");
    public static final KeyMapping viewBind = new KeyMapping("key.zergatul.freecam.viewbind", InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, "category.zergatul.freecam");
    public static final KeyMapping breakKey = new KeyMapping("key.zergatul.freecam.break", InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_LEFT, "category.zergatul.freecam");
    public static final KeyMapping placeKey = new KeyMapping("key.zergatul.freecam.place", InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_RIGHT, "category.zergatul.freecam");
}