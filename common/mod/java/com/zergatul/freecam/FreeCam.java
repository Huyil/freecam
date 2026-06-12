package com.zergatul.freecam;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.*;
import net.minecraft.client.player.Input;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class FreeCam {

    public static final FreeCam instance = new FreeCam();

    private final static int REMEMBER_STATE_DELAY_MS = 400;

    private final Minecraft mc = Minecraft.getInstance();
    private final Quaternionf rotation = new Quaternionf(0.0F, 0.0F, 0.0F, 1.0F);
    private final Vector3f forwards = new Vector3f(0.0F, 0.0F, 1.0F);
    private final Vector3f up = new Vector3f(0.0F, 1.0F, 0.0F);
    private final Vector3f left = new Vector3f(1.0F, 0.0F, 0.0F);
    private final FreeCamPath path = new FreeCamPath(this);
    private final FreeCamConfig config = ConfigRepository.instance.load();
    private boolean active;
    private CameraType oldCameraType;
    private Input playerInput;
    private Input freecamInput;
    private double x, y, z;
    private float yRot, xRot;
    private double forwardVelocity;
    private double leftVelocity;
    private double upVelocity;
    private long lastTime;
    private boolean freecamHitResultPicking;
    private boolean cameraLock;
    private boolean eyeLock;
    private boolean followCamera;
    private double followDeltaX, followDeltaY, followDeltaZ;
    private boolean gameRendererPicking;
    private boolean moveAlongPath;
    private long pathStartTime;
    private long dontMoveFreeCamBefore;
    private Vec3 mouseRayDirection;
    private boolean middleActive;
    private boolean middleDragged;
    private double middleStartX, middleStartY;
    private double prevDragX, prevDragY;
    private boolean middleTickWasDown;
    private boolean prevArrowLeft, prevArrowRight, prevArrowUp, prevArrowDown;
    private float targetYRot, targetXRot;
    private int rtsUseBoostCounter;
    private int lastBoostY = Integer.MIN_VALUE;

    private FreeCam() {

    }

    public boolean isActive() {
        return active;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public float getXRot() {
        return xRot;
    }

    public float getYRot() {
        return yRot;
    }

    public Vec3 getPos() {
        return new Vec3(x, y, z);
    }

    public BlockPos getBlockPos() {
        return new BlockPos((int) x, (int) y, (int) z);
    }

    public FreeCamConfig getConfig() {
        return config;
    }

    public FreeCamPath getPath() {
        return path;
    }

    public void toggle() {
        if (active) {
            disable();
        } else {
            enable();
        }
    }

    public void toggleCameraLock() {
        assert mc.player != null;

        if (active && !followCamera) {
            cameraLock = !cameraLock;
            if (cameraLock) {
                mc.player.input = playerInput;
            } else {
                mc.player.input = freecamInput;
            }
        }
    }

    public void toggleEyeLock() {
        if (active && !followCamera) {
            eyeLock = !eyeLock;
        }
    }

    public void toggleFollowCamera() {
        assert mc.player != null;

        if (active) {
            followCamera = !followCamera;
            if (followCamera) {
                mc.player.input = playerInput;
                cameraLock = false;
                eyeLock = false;

                Entity entity = mc.getCameraEntity();
                if (entity == null) {
                    return;
                }

                Vec3 pos = entity.getEyePosition();
                followDeltaX = x - pos.x;
                followDeltaY = y - pos.y;
                followDeltaZ = z - pos.z;
            } else {
                mc.player.input = freecamInput;
            }
        }
    }

    public void enable() {
        if (active) {
            return;
        }

        Entity entity = mc.getCameraEntity();
        if (entity == null) {
            return;
        }

        active = true;
        cameraLock = false;
        eyeLock = false;
        followCamera = false;
        oldCameraType = mc.options.getCameraType();
        playerInput = mc.player.input;
        mc.player.input = freecamInput = createFreeCamInput(playerInput);
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        if (oldCameraType.isFirstPerson() != mc.options.getCameraType().isFirstPerson()) {
            mc.gameRenderer.checkEntityPostEffect(mc.options.getCameraType().isFirstPerson() ? mc.getCameraEntity() : null);
        }

        if (config.rememberInputState) {
            dontMoveFreeCamBefore = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REMEMBER_STATE_DELAY_MS);
        }

        float partialTicks = mc.getTimer().getGameTimeDeltaPartialTick(true);
        Vec3 pos = entity.getEyePosition(partialTicks);
        x = pos.x;
        y = pos.y;
        z = pos.z;
        yRot = entity.getViewYRot(partialTicks);
        xRot = entity.getViewXRot(partialTicks);

        calculateVectors();

        double distance = -2;
        x += (double)this.forwards.x() * distance;
        y += (double)this.forwards.y() * distance;
        z += (double)this.forwards.z() * distance;

        forwardVelocity = 0;
        leftVelocity = 0;
        upVelocity = 0;
        lastTime = 0;

        targetYRot = yRot;
        targetXRot = xRot;

        if (config.rtsMode) {
            mouseRayDirection = new Vec3(0, 0, 1);
            mc.mouseHandler.releaseMouse();
        }
    }

    public void disable() {
        assert mc.player != null;

        if (!active) {
            return;
        }

        active = false;
        CameraType cameraType = mc.options.getCameraType();
        mc.options.setCameraType(oldCameraType);
        mc.player.input = playerInput;
        if (cameraType.isFirstPerson() != mc.options.getCameraType().isFirstPerson()) {
            mc.gameRenderer.checkEntityPostEffect(mc.options.getCameraType().isFirstPerson() ? mc.getCameraEntity() : null);
        }
        oldCameraType = null;

        if (config.rtsMode) {
            mouseRayDirection = null;
            mc.mouseHandler.grabMouse();
            middleActive = false;
            middleDragged = false;
            middleTickWasDown = false;
        }

        targetYRot = 0;
        targetXRot = 0;
    }

    public void onHandleKeyBindings() {
        if (mc.player == null) {
            return;
        }
        if (mc.screen != null) {
            return;
        }
        while (KeyBindings.toggleFreeCam.consumeClick()) {
            toggle();
        }
        while (KeyBindings.toggleCameraLock.consumeClick()) {
            toggleCameraLock();
        }
        while (KeyBindings.toggleEyeLock.consumeClick()) {
            toggleEyeLock();
        }
        while (KeyBindings.toggleFollowCam.consumeClick()) {
            toggleFollowCamera();
        }
        while (KeyBindings.startPath.consumeClick()) {
            startPath();
        }
    }

    public boolean onPlayerTurn(double yRot, double xRot) {
        if (active && !cameraLock && !followCamera) {
            if (config.rtsMode) {
                return false;
            }
            if (!eyeLock && !moveAlongPath) {
                this.xRot += (float) xRot * 0.15F;
                this.yRot += (float) yRot * 0.15F;
                this.xRot = Mth.clamp(this.xRot, -90, 90);
                calculateVectors();
            }
            return false;
        } else {
            return true;
        }
    }

    public boolean onRenderCrosshairModifyIsFirstPerson(boolean value) {
        if (active) {
            if (config.rtsMode) return false;
            return !cameraLock && !eyeLock && !followCamera && config.target;
        } else {
            return value;
        }
    }

    public boolean onRenderItemInHandIsFirstPerson(CameraType cameraType) {
        if (active && config.renderHands && !cameraLock && !eyeLock && !followCamera) {
            return true;
        } else {
            return cameraType.isFirstPerson();
        }
    }

    public boolean shouldShowMyName() {
        return active && config.showMyName;
    }

    public void onRenderTickStart(DeltaTracker delta) {
        if (!active) {
            return;
        }

        if (lastTime == 0) {
            lastTime = System.nanoTime();
            return;
        }

        long currTime = System.nanoTime();
        float frameTime = (currTime - lastTime) / 1e9f;
        lastTime = currTime;

        if (moveAlongPath) {
            FreeCamPath.Entry entry = path.interpolate((currTime - pathStartTime) / 1e6);
            if (entry == null) {
                moveAlongPath = false;
            } else {
                x = entry.position().x;
                y = entry.position().y;
                z = entry.position().z;
                xRot = (float) entry.xRot();
                yRot = (float) entry.yRot();
            }
        } else if (followCamera) {
            Entity entity = mc.getCameraEntity();
            if (entity != null) {
                Vec3 pos = entity.getEyePosition(delta.getGameTimeDeltaPartialTick(true));
                x = pos.x + followDeltaX;
                y = pos.y + followDeltaY;
                z = pos.z + followDeltaZ;
            }
        } else {
            Input input = playerInput;
            float forwardImpulse = !cameraLock ? (input.up ? 1 : 0) + (input.down ? -1 : 0) : 0;
            float leftImpulse = !cameraLock ? (input.left ? 1 : 0) + (input.right ? -1 : 0) : 0;
            float upImpulse = !cameraLock ? ((input.jumping ? 1 : 0) + (input.shiftKeyDown ? -1 : 0)) : 0;

            double fwdMax = config.maxSpeed * config.speedForward;
            double strafeMax = config.maxSpeed * config.speedStrafe;
            double vertMax = config.maxSpeed * config.speedVertical;

            double slowdown;
            if (config.inertia <= 0.001) {
                slowdown = 0.0;
            } else {
                double slowPerSec = Math.pow(config.slowdownFactor, 1.0 / config.inertia);
                slowdown = Math.pow(slowPerSec, frameTime);
            }
            forwardVelocity = combineMovement(forwardVelocity, forwardImpulse, frameTime, config.acceleration, slowdown);
            leftVelocity = combineMovement(leftVelocity, leftImpulse, frameTime, config.acceleration, slowdown);
            upVelocity = combineMovement(upVelocity, upImpulse, frameTime, config.acceleration, slowdown);
            forwardVelocity = Mth.clamp(forwardVelocity, -fwdMax, fwdMax);
            leftVelocity = Mth.clamp(leftVelocity, -strafeMax, strafeMax);
            upVelocity = Mth.clamp(upVelocity, -vertMax, vertMax);

            double dx;
            double dy;
            double dz;
            if (config.rtsMode) {
                float fwdX = this.forwards.x();
                float fwdZ = this.forwards.z();
                float fwdLen = (float)Math.sqrt(fwdX * fwdX + fwdZ * fwdZ);
                if (fwdLen > 1e-4f) {
                    fwdX /= fwdLen;
                    fwdZ /= fwdLen;
                }
                dx = fwdX * forwardVelocity + (double) this.left.x() * leftVelocity;
                dy = upVelocity;
                dz = fwdZ * forwardVelocity + (double) this.left.z() * leftVelocity;
            } else {
                dx = (double) this.forwards.x() * forwardVelocity + (double) this.left.x() * leftVelocity;
                dy = (double) this.forwards.y() * forwardVelocity + upVelocity + (double) this.left.y() * leftVelocity;
                dz = (double) this.forwards.z() * forwardVelocity + (double) this.left.z() * leftVelocity;
            }
            dx *= frameTime;
            dy *= frameTime;
            dz *= frameTime;
            if (!config.rememberInputState || dontMoveFreeCamBefore < currTime) {
                x += dx;
                y += dy;
                z += dz;
            }
        }

        applyEyeLock(delta.getGameTimeDeltaPartialTick(true));

        if (config.rtsMode && !cameraLock && !eyeLock && !followCamera && !moveAlongPath) {
            boolean arrowLeft = isKeyDown(GLFW.GLFW_KEY_LEFT);
            boolean arrowRight = isKeyDown(GLFW.GLFW_KEY_RIGHT);
            boolean arrowUp = isKeyDown(GLFW.GLFW_KEY_UP);
            boolean arrowDown = isKeyDown(GLFW.GLFW_KEY_DOWN);

            if (arrowLeft && !prevArrowLeft) {
                targetYRot = (float)(Math.floor((targetYRot - 0.01) / 15.0) * 15.0);
            }
            if (arrowRight && !prevArrowRight) {
                targetYRot = (float)(Math.ceil((targetYRot + 0.01) / 15.0) * 15.0);
            }
            if (arrowUp && !prevArrowUp) {
                targetXRot = (float)(Math.floor((targetXRot - 0.01) / 15.0) * 15.0);
                if (targetXRot < -90) targetXRot = -90;
            }
            if (arrowDown && !prevArrowDown) {
                targetXRot = (float)(Math.ceil((targetXRot + 0.01) / 15.0) * 15.0);
                if (targetXRot > 90) targetXRot = 90;
            }

            prevArrowLeft = arrowLeft;
            prevArrowRight = arrowRight;
            prevArrowUp = arrowUp;
            prevArrowDown = arrowDown;

            double currX = mc.mouseHandler.xpos();
            double currY = mc.mouseHandler.ypos();
            boolean middleDown = isMouseButtonDown(GLFW.GLFW_MOUSE_BUTTON_MIDDLE);

            if (middleDown) {
                if (!middleActive) {
                    middleActive = true;
                    middleStartX = currX;
                    middleStartY = currY;
                    middleDragged = false;
                    prevDragX = currX;
                    prevDragY = currY;
                } else {
                    double distSq = (currX - middleStartX) * (currX - middleStartX) +
                                    (currY - middleStartY) * (currY - middleStartY);
                    if (distSq > 25.0) {
                        middleDragged = true;
                    }
                    if (middleDragged) {
                        double dx = currX - prevDragX;
                        double dy = currY - prevDragY;
                        targetYRot += (float)dx * 0.3f;
                        targetXRot = Mth.clamp(targetXRot + (float)dy * 0.3f, -90, 90);
                    }
                    prevDragX = currX;
                    prevDragY = currY;
                }
            } else {
                middleActive = false;
            }

            float lerp = 1.0f - (float)Math.exp(-frameTime * 18.0);
            float deltaY = targetYRot - yRot;
            float deltaX = targetXRot - xRot;
            if (Math.abs(deltaY) >= 0.02f || Math.abs(deltaX) >= 0.02f) {
                yRot += deltaY * lerp;
                xRot += deltaX * lerp;
                calculateVectors();
            } else if (yRot != targetYRot || xRot != targetXRot) {
                yRot = targetYRot;
                xRot = targetXRot;
                calculateVectors();
            }

            computeMouseRayDirection();
        }
    }

    public void onClientTickStart() {
        if (active) {
            disableKey(mc.options.keyTogglePerspective);

            if (config.rtsMode) {
                boolean middleDown = isMouseButtonDown(GLFW.GLFW_MOUSE_BUTTON_MIDDLE);
                if (middleDown) {
                    disableKey(mc.options.keyPickItem);
                    middleTickWasDown = true;
                } else {
                    if (middleTickWasDown && !middleDragged) {
                        mc.options.keyPickItem.click(InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_MIDDLE));
                    }
                    middleTickWasDown = false;
                    middleDragged = false;
                }

                if (isMouseButtonDown(GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
                    int currentY = Integer.MIN_VALUE;
                    if (mc.hitResult instanceof BlockHitResult bhr) {
                        currentY = bhr.getBlockPos().getY();
                    }
                    if (currentY == lastBoostY) {
                        rtsUseBoostCounter++;
                        if (rtsUseBoostCounter >= 2) {
                            rtsUseBoostCounter = 0;
                            mc.options.keyUse.click(InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_RIGHT));
                        }
                    } else {
                        rtsUseBoostCounter = 0;
                    }
                    lastBoostY = currentY;
                } else {
                    rtsUseBoostCounter = 0;
                    lastBoostY = Integer.MIN_VALUE;
                }
            }

            playerInput.tick(false, 0);
        }
    }

    public void onWorldUnload() {
        disable();
    }

    public void onRenderWorldLast(Matrix4f pose, Matrix4f projectionMatrix, Camera camera) {
        if (!active || moveAlongPath) {
            return;
        }

        List<FreeCamPath.Entry> path = getPath().get();
        if (path.size() < 2) {
            return;
        }

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tesselator.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        RenderSystem.setShaderColor(1f, 1.0f, 1f, 1f);

        Vec3 view = camera.getPosition();
        for (int i = 1; i < path.size(); i++) {
            FreeCamPath.Entry e1 = path.get(i - 1);
            FreeCamPath.Entry e2 = path.get(i);

            bufferBuilder.addVertex(
                            (float) (e1.position().x - view.x),
                            (float) (e1.position().y - view.y),
                            (float) (e1.position().z - view.z))
                    .setColor(1, 1, 1, 1f);
            bufferBuilder.addVertex(
                            (float) (e2.position().x - view.x),
                            (float) (e2.position().y - view.y),
                            (float) (e2.position().z - view.z))
                    .setColor(1, 1, 1, 1f);
        }

        renderLines(bufferBuilder, pose, projectionMatrix);
    }

    private void startPath() {
        if (!active) {
            return;
        }

        moveAlongPath = true;
        pathStartTime = System.nanoTime();
    }

    public boolean shouldOverrideCameraEntityPosition(Entity entity) {
        if (active && !cameraLock && !eyeLock && !followCamera && (config.target || config.rtsMode)) {
            return entity == mc.getCameraEntity() && gameRendererPicking || freecamHitResultPicking;
        } else {
            return false;
        }
    }

    public void onRenderDebugScreenLeft(List<String> list) {
        if (active) {
            list.add("");
            String coordinates = String.format(Locale.ROOT, "Free Cam XYZ: %.3f / %.5f / %.3f", x, y, z);
            list.add(coordinates);
        }
    }

    public void onRenderDebugScreenRight(List<String> list) {
        if (!active) {
            return;
        }
        if (cameraLock || eyeLock || followCamera) {
            return;
        }
        if (!config.target && !config.rtsMode) {
            return;
        }

        // TODO: remove and just use free cam target as normal?
        freecamHitResultPicking = true;
        try {
            HitResult hit = mc.player.pick(20.0D, 0.0F, false);
            if (hit.getType() == HitResult.Type.BLOCK) {
                BlockPos pos = ((BlockHitResult)hit).getBlockPos();
                BlockState state = mc.level.getBlockState(pos);
                list.add("");
                list.add(ChatFormatting.UNDERLINE + "Free Cam Targeted Block: " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
                list.add(String.valueOf(ModApiWrapper.instance.BLOCKS.getKey(state.getBlock())));

                for (var entry: state.getValues().entrySet()) {
                    list.add(getPropertyValueString(entry));
                }

                state.getTags().map(tag -> "#" + tag.location()).forEach(list::add);
            }
        }
        finally {
            freecamHitResultPicking = false;
        }
    }

    public Vec3 getRTSPickDirection() {
        if (active && config.rtsMode && mouseRayDirection != null) {
            return mouseRayDirection;
        }
        return null;
    }

    public boolean shouldPreventMouseGrab() {
        return active && config.rtsMode;
    }

    public boolean shouldForceContinueAttack() {
        return active && config.rtsMode;
    }

    public void onRtsModeChanged() {
        if (active) {
            if (config.rtsMode) {
                if (mouseRayDirection == null) {
                    mouseRayDirection = new Vec3(0, 0, 1);
                }
                if (mc.screen == null) {
                    mc.mouseHandler.releaseMouse();
                }
            } else {
                mouseRayDirection = null;
                middleActive = false;
                middleDragged = false;
                middleTickWasDown = false;
                if (mc.screen == null) {
                    mc.mouseHandler.grabMouse();
                }
            }
        }
    }

    private void computeMouseRayDirection() {
        double mouseX = mc.mouseHandler.xpos();
        double mouseY = mc.mouseHandler.ypos();

        int screenWidth = mc.getWindow().getScreenWidth();
        int screenHeight = mc.getWindow().getScreenHeight();

        if (screenWidth <= 0 || screenHeight <= 0) {
            mouseRayDirection = new Vec3(0, 0, 1);
            return;
        }

        float ndcX = (float) (2.0 * mouseX / screenWidth - 1.0);
        float ndcY = (float) (1.0 - 2.0 * mouseY / screenHeight);

        double fovRad = mc.options.fov().get() * Math.PI / 180.0;
        double tanHalfFov = Math.tan(fovRad / 2.0);
        double aspect = (double) screenWidth / screenHeight;

        float dx = (float)(ndcX * tanHalfFov * aspect);
        float dy = (float)(ndcY * tanHalfFov);

        Vector3f dir = new Vector3f(-dx, dy, 1.0f).normalize();
        dir.rotate(rotation);

        mouseRayDirection = new Vec3(dir.x(), dir.y(), dir.z());
    }

    private boolean isKeyDown(int keyCode) {
        return GLFW.glfwGetKey(mc.getWindow().getWindow(), keyCode) == GLFW.GLFW_PRESS;
    }

    private boolean isMouseButtonDown(int button) {
        return GLFW.glfwGetMouseButton(mc.getWindow().getWindow(), button) == GLFW.GLFW_PRESS;
    }

    public void onBeforeGameRendererPick() {
        gameRendererPicking = true;
    }

    public void onAfterGameRendererPick() {
        gameRendererPicking = false;
    }

    private Input createFreeCamInput(Input playerInput) {
        if (config.rememberInputState) {
            Input input = new Input();
            input.up = playerInput.up;
            input.down = playerInput.down;
            input.left = playerInput.left;
            input.right = playerInput.right;
            input.jumping = playerInput.jumping;
            input.shiftKeyDown = playerInput.shiftKeyDown;
            input.forwardImpulse = playerInput.forwardImpulse;
            input.leftImpulse = playerInput.leftImpulse;
            return input;
        } else {
            return new Input();
        }
    }

    private void applyEyeLock(float partialTicks) {
        if (!eyeLock) {
            return;
        }

        Entity entity = mc.getCameraEntity();
        if (entity == null) {
            return;
        }

        Vec3 pos = entity.getEyePosition(partialTicks);
        double dx = x - pos.x;
        double dy = y - pos.y;
        double dz = z - pos.z;
        this.xRot = (float) (Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) / Math.PI * 180);
        this.yRot = (float) (Math.atan2(dz, dx) / Math.PI * 180 + 90);
        this.xRot = Mth.clamp(this.xRot, -90, 90);
        calculateVectors();
    }

    private void calculateVectors() {
        rotation.rotationYXZ(-yRot * ((float)Math.PI / 180F), (config.spectatorMovement ? xRot : 0) * ((float)Math.PI / 180F), 0.0F);
        forwards.set(0.0F, 0.0F, 1.0F).rotate(rotation);
        up.set(0.0F, 1.0F, 0.0F).rotate(rotation);
        left.set(1.0F, 0.0F, 0.0F).rotate(rotation);
    }

    private double combineMovement(double velocity, double impulse, double frameTime, double acceleration, double slowdown) {
        if (impulse != 0) {
            if (impulse > 0 && velocity < 0) {
                velocity = 0;
            }
            if (impulse < 0 && velocity > 0) {
                velocity = 0;
            }
            velocity += acceleration * impulse * frameTime;
        } else {
            velocity *= slowdown;
        }
        return velocity;
    }

    private String getPropertyValueString(Map.Entry<Property<?>, Comparable<?>> p_94072_) {
        Property<?> property = p_94072_.getKey();
        Comparable<?> comparable = p_94072_.getValue();
        String s = Util.getPropertyName(property, comparable);
        if (Boolean.TRUE.equals(comparable)) {
            s = ChatFormatting.GREEN + s;
        } else if (Boolean.FALSE.equals(comparable)) {
            s = ChatFormatting.RED + s;
        }

        return property.getName() + ": " + s;
    }

    private void disableKey(KeyMapping key) {
        while (key.consumeClick()) {}
        key.setDown(false);
    }

    private void renderLines(BufferBuilder bufferBuilder, Matrix4f pose, Matrix4f projection) {
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        GL11.glEnable(GL11.GL_LINE_SMOOTH);

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        SharedVertexBuffer.instance.bind();
        SharedVertexBuffer.instance.upload(bufferBuilder.buildOrThrow());
        SharedVertexBuffer.instance.drawWithShader(pose, projection, GameRenderer.getPositionColorShader());
        VertexBuffer.unbind();

        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
    }

    private static class SharedVertexBuffer {
        public static final VertexBuffer instance = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
    }
}