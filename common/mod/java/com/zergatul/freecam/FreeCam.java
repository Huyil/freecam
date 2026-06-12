package com.zergatul.freecam;

import com.mojang.blaze3d.platform.InputConstants;
import com.zergatul.freecam.mixins.MixinMinecraftInvoker;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.*;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.Input;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class FreeCam {

    public static final FreeCam instance = new FreeCam();

    private final static int REMEMBER_STATE_DELAY_MS = 400;
    private final static float MAX_PITCH = 85.0f;

    private final Minecraft mc = Minecraft.getInstance();
    private final Quaternionf rotation = new Quaternionf(0.0F, 0.0F, 0.0F, 1.0F);
    private final Quaternionf viewRotation = new Quaternionf(0.0F, 0.0F, 0.0F, 1.0F);
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
    private boolean viewBound;
    private double middleTotalMovement;
    private boolean middleConsumedThisHold;
    private boolean prevArrowLeft, prevArrowRight, prevArrowUp, prevArrowDown;
    private float targetYRot, targetXRot;
    private int rtsUseBoostCounter;
    private int lastBoostY = Integer.MIN_VALUE;
    private final List<KeyMapping> suppressedKeys = new ArrayList<>();

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
            if (viewBound) {
                viewBound = false;
                mc.mouseHandler.releaseMouse();
            }
            mc.mouseHandler.grabMouse();
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
            if (viewBound) {
                this.xRot += (float) xRot * 0.15F;
                this.yRot += (float) yRot * 0.15F;
                this.xRot = Mth.clamp(this.xRot, -90, 90);
                middleTotalMovement += Math.abs(yRot) + Math.abs(xRot);
                calculateVectors();
                targetYRot = this.yRot;
                targetXRot = this.xRot;
                return false;
            }
            if (config.rtsMode) {
                return false;
            }
            if (!eyeLock && !moveAlongPath) {
                this.xRot += (float) xRot * 0.15F;
                this.yRot += (float) yRot * 0.15F;
                this.xRot = Mth.clamp(this.xRot, -90, 90);
                calculateVectors();
                targetYRot = this.yRot;
                targetXRot = this.xRot;
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

        if (eyeLock) {
            targetYRot = yRot;
            targetXRot = xRot;
        }

        if (config.rtsMode && !moveAlongPath) {
            boolean canRotate = !cameraLock && !eyeLock;

            if (canRotate) {
                boolean arrowLeft = KeyBindings.rotateLeft.isDown();
                boolean arrowRight = KeyBindings.rotateRight.isDown();
                boolean arrowUp = KeyBindings.rotateUp.isDown();
                boolean arrowDown = KeyBindings.rotateDown.isDown();

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

                boolean viewBindDown = KeyBindings.viewBind.isDown();

                if (viewBindDown && !viewBound && mc.screen == null) {
                    viewBound = true;
                    middleTotalMovement = 0;
                    middleConsumedThisHold = false;
                    suppressedKeys.clear();
                    InputConstants.Key bindKey = KeyBindings.viewBind.getKey();
                    if (bindKey != InputConstants.UNKNOWN) {
                        collectConflictingKeys(bindKey, suppressedKeys);
                    }
                    mc.mouseHandler.grabMouse();
                } else if (!viewBindDown && viewBound) {
                    viewBound = false;
                    if (mc.screen == null) {
                        mc.mouseHandler.releaseMouse();
                        if (middleTotalMovement < 3.0 && !middleConsumedThisHold && mc.player != null) {
                            if (isMainHandPlaceable()) {
                                ((MixinMinecraftInvoker) mc).freecam$startUseItem();
                            } else {
                                ((MixinMinecraftInvoker) mc).freecam$startAttack();
                            }
                        }
                    }
                    suppressedKeys.clear();
                }

                if (viewBound) {
                    targetYRot = yRot;
                    targetXRot = xRot;
                } else {
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
                }
            }

            computeMouseRayDirection();
        }
    }

    public void onClientTickStart() {
        if (active) {
            disableKey(mc.options.keyTogglePerspective);

            if (config.rtsMode) {
                if (config.playerViewFollow && !viewBound && !moveAlongPath && mc.hitResult != null && mc.player != null) {
                    Vec3 playerEye = mc.player.getEyePosition();
                    Vec3 target = mc.hitResult.getLocation();
                    double dx = target.x - playerEye.x;
                    double dy = target.y - playerEye.y;
                    double dz = target.z - playerEye.z;
                    double horiz = Math.sqrt(dx * dx + dz * dz);
                    if (horiz > 0.001 || Math.abs(dy) > 0.001) {
                        float newYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                        float newPitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
                        newPitch = Mth.clamp(newPitch, -MAX_PITCH, MAX_PITCH);
                        mc.player.setYRot(newYaw);
                        mc.player.setXRot(newPitch);
                        mc.player.setYHeadRot(newYaw);
                    }
                }

                if (viewBound || KeyBindings.viewBind.isDown()) {
                    for (KeyMapping km : suppressedKeys) {
                        disableKey(km);
                    }
                }

                if (!viewBound && mc.screen == null && mc.player != null) {
                    handleBreakPlace();
                }

                if (!viewBound && KeyBindings.placeKey.isDown()) {
                    int currentY = Integer.MIN_VALUE;
                    if (mc.hitResult instanceof BlockHitResult bhr) {
                        currentY = bhr.getBlockPos().getY();
                    }
                    if (currentY == lastBoostY) {
                        rtsUseBoostCounter++;
                        if (rtsUseBoostCounter >= 2) {
                            rtsUseBoostCounter = 0;
                            ((MixinMinecraftInvoker) mc).freecam$startUseItem();
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
        if (!active) {
            return;
        }

        Vec3 cam = camera.getPosition();

        if (!viewBound && config.highlightOpacity > 0.001) {
            float opacity = (float) config.highlightOpacity;
            if (mc.hitResult instanceof EntityHitResult ehr) {
                renderEntityHighlight(pose, projectionMatrix, cam, ehr.getEntity(), opacity);
            } else if (mc.hitResult instanceof BlockHitResult bhr
                    && bhr.getType() == HitResult.Type.BLOCK
                    && mc.level != null) {
                renderBlockHighlight(pose, projectionMatrix, cam, bhr.getBlockPos(), opacity);
            }
        }

        if (!moveAlongPath) {
            List<FreeCamPath.Entry> path = getPath().get();
            if (path.size() >= 2) {
                renderPathLines(pose, projectionMatrix, cam, path);
            }
        }
    }

    private void renderBlockHighlight(Matrix4f pose, Matrix4f projection, Vec3 cam, BlockPos pos, float opacity) {
        float x0 = (float)(pos.getX() - cam.x) - 0.002f;
        float y0 = (float)(pos.getY() - cam.y) - 0.002f;
        float z0 = (float)(pos.getZ() - cam.z) - 0.002f;
        float x1 = x0 + 1.004f;
        float y1 = y0 + 1.004f;
        float z1 = z0 + 1.004f;

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bb = tesselator.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        boolean ctrl = active && config.rtsMode && KeyBindings.forceSelect.isDown();
        float r = ctrl ? 1.0f : 0.0f;
        float g = ctrl ? 0.3f : 1.0f;
        float b = ctrl ? 1.0f : 1.0f;
        float a = opacity;

        bb.addVertex(x0,y0,z0).setColor(r,g,b,a); bb.addVertex(x1,y0,z0).setColor(r,g,b,a);
        bb.addVertex(x1,y0,z0).setColor(r,g,b,a); bb.addVertex(x1,y0,z1).setColor(r,g,b,a);
        bb.addVertex(x1,y0,z1).setColor(r,g,b,a); bb.addVertex(x0,y0,z1).setColor(r,g,b,a);
        bb.addVertex(x0,y0,z1).setColor(r,g,b,a); bb.addVertex(x0,y0,z0).setColor(r,g,b,a);
        bb.addVertex(x0,y1,z0).setColor(r,g,b,a); bb.addVertex(x1,y1,z0).setColor(r,g,b,a);
        bb.addVertex(x1,y1,z0).setColor(r,g,b,a); bb.addVertex(x1,y1,z1).setColor(r,g,b,a);
        bb.addVertex(x1,y1,z1).setColor(r,g,b,a); bb.addVertex(x0,y1,z1).setColor(r,g,b,a);
        bb.addVertex(x0,y1,z1).setColor(r,g,b,a); bb.addVertex(x0,y1,z0).setColor(r,g,b,a);
        bb.addVertex(x0,y0,z0).setColor(r,g,b,a); bb.addVertex(x0,y1,z0).setColor(r,g,b,a);
        bb.addVertex(x1,y0,z0).setColor(r,g,b,a); bb.addVertex(x1,y1,z0).setColor(r,g,b,a);
        bb.addVertex(x1,y0,z1).setColor(r,g,b,a); bb.addVertex(x1,y1,z1).setColor(r,g,b,a);
        bb.addVertex(x0,y0,z1).setColor(r,g,b,a); bb.addVertex(x0,y1,z1).setColor(r,g,b,a);

        GL11.glLineWidth(3.0f);
        renderLines(bb, pose, projection);
        GL11.glLineWidth(1.0f);
    }

    private void renderEntityHighlight(Matrix4f pose, Matrix4f projection, Vec3 cam, Entity entity, float opacity) {
        AABB box = entity.getBoundingBox();
        float x0 = (float)(box.minX - cam.x) - 0.002f;
        float y0 = (float)(box.minY - cam.y) - 0.002f;
        float z0 = (float)(box.minZ - cam.z) - 0.002f;
        float x1 = (float)(box.maxX - cam.x) + 0.002f;
        float y1 = (float)(box.maxY - cam.y) + 0.002f;
        float z1 = (float)(box.maxZ - cam.z) + 0.002f;

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bb = tesselator.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        boolean ctrl = active && config.rtsMode && KeyBindings.forceSelect.isDown();
        float r = ctrl ? 1.0f : 1.0f;
        float g = ctrl ? 0.3f : 0.5f;
        float b = ctrl ? 1.0f : 0.0f;
        float a = opacity;

        bb.addVertex(x0,y0,z0).setColor(r,g,b,a); bb.addVertex(x1,y0,z0).setColor(r,g,b,a);
        bb.addVertex(x1,y0,z0).setColor(r,g,b,a); bb.addVertex(x1,y0,z1).setColor(r,g,b,a);
        bb.addVertex(x1,y0,z1).setColor(r,g,b,a); bb.addVertex(x0,y0,z1).setColor(r,g,b,a);
        bb.addVertex(x0,y0,z1).setColor(r,g,b,a); bb.addVertex(x0,y0,z0).setColor(r,g,b,a);
        bb.addVertex(x0,y1,z0).setColor(r,g,b,a); bb.addVertex(x1,y1,z0).setColor(r,g,b,a);
        bb.addVertex(x1,y1,z0).setColor(r,g,b,a); bb.addVertex(x1,y1,z1).setColor(r,g,b,a);
        bb.addVertex(x1,y1,z1).setColor(r,g,b,a); bb.addVertex(x0,y1,z1).setColor(r,g,b,a);
        bb.addVertex(x0,y1,z1).setColor(r,g,b,a); bb.addVertex(x0,y1,z0).setColor(r,g,b,a);
        bb.addVertex(x0,y0,z0).setColor(r,g,b,a); bb.addVertex(x0,y1,z0).setColor(r,g,b,a);
        bb.addVertex(x1,y0,z0).setColor(r,g,b,a); bb.addVertex(x1,y1,z0).setColor(r,g,b,a);
        bb.addVertex(x1,y0,z1).setColor(r,g,b,a); bb.addVertex(x1,y1,z1).setColor(r,g,b,a);
        bb.addVertex(x0,y0,z1).setColor(r,g,b,a); bb.addVertex(x0,y1,z1).setColor(r,g,b,a);

        GL11.glLineWidth(3.0f);
        renderLines(bb, pose, projection);
        GL11.glLineWidth(1.0f);
    }

    private void renderPathLines(Matrix4f pose, Matrix4f projection, Vec3 cam, List<FreeCamPath.Entry> path) {
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bb = tesselator.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 1; i < path.size(); i++) {
            FreeCamPath.Entry e1 = path.get(i - 1);
            FreeCamPath.Entry e2 = path.get(i);
            bb.addVertex(
                            (float) (e1.position().x - cam.x),
                            (float) (e1.position().y - cam.y),
                            (float) (e1.position().z - cam.z))
                    .setColor(1, 1, 1, 1f);
            bb.addVertex(
                            (float) (e2.position().x - cam.x),
                            (float) (e2.position().y - cam.y),
                            (float) (e2.position().z - cam.z))
                    .setColor(1, 1, 1, 1f);
        }
        renderLines(bb, pose, projection);
    }

    private void startPath() {
        if (!active) {
            return;
        }

        moveAlongPath = true;
        pathStartTime = System.nanoTime();
    }

    public boolean shouldOverrideCameraEntityPosition(Entity entity) {
        if (active && (config.target || config.rtsMode)) {
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
        return active && config.rtsMode && !viewBound;
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
                viewBound = false;
                if (mc.screen == null) {
                    mc.mouseHandler.grabMouse();
                }
            }
        }
    }

    private void collectConflictingKeys(InputConstants.Key key, List<KeyMapping> result) {
        Options o = mc.options;
        tryAddConflict(o.keyAttack, key, result);
        tryAddConflict(o.keyUse, key, result);
        tryAddConflict(o.keyPickItem, key, result);
        tryAddConflict(o.keyInventory, key, result);
        tryAddConflict(o.keyDrop, key, result);
        tryAddConflict(o.keyChat, key, result);
        tryAddConflict(o.keySwapOffhand, key, result);
        tryAddConflict(o.keyUp, key, result);
        tryAddConflict(o.keyDown, key, result);
        tryAddConflict(o.keyLeft, key, result);
        tryAddConflict(o.keyRight, key, result);
        tryAddConflict(o.keyJump, key, result);
        tryAddConflict(o.keyShift, key, result);
        tryAddConflict(o.keySprint, key, result);
        tryAddConflict(o.keyPlayerList, key, result);
        tryAddConflict(o.keyTogglePerspective, key, result);
        tryAddConflict(KeyBindings.toggleFreeCam, key, result);
        tryAddConflict(KeyBindings.toggleCameraLock, key, result);
        tryAddConflict(KeyBindings.toggleEyeLock, key, result);
        tryAddConflict(KeyBindings.toggleFollowCam, key, result);
        tryAddConflict(KeyBindings.startPath, key, result);
        tryAddConflict(KeyBindings.forceSelect, key, result);
        tryAddConflict(KeyBindings.rotateLeft, key, result);
        tryAddConflict(KeyBindings.rotateRight, key, result);
        tryAddConflict(KeyBindings.rotateUp, key, result);
        tryAddConflict(KeyBindings.rotateDown, key, result);
    }

    private void tryAddConflict(KeyMapping km, InputConstants.Key key, List<KeyMapping> result) {
        if (km == KeyBindings.viewBind) return;
        if (key.equals(km.getKey())) {
            result.add(km);
        }
    }

    private boolean isMainHandPlaceable() {
        ItemStack mainHand = mc.player.getMainHandItem();
        return !mainHand.isEmpty() && mainHand.getItem() instanceof BlockItem;
    }

    private void handleBreakPlace() {
        InputConstants.Key bk = KeyBindings.breakKey.getKey();
        InputConstants.Key pk = KeyBindings.placeKey.getKey();
        InputConstants.Key attackKey = mc.options.keyAttack.getKey();
        InputConstants.Key useKey = mc.options.keyUse.getKey();
        InputConstants.Key pickKey = mc.options.keyPickItem.getKey();

        if (bk.equals(attackKey)) {
            while (mc.options.keyAttack.consumeClick()) {}
        }
        if (pk.equals(useKey)) {
            while (mc.options.keyUse.consumeClick()) {}
        }
        if (bk.equals(pickKey) || pk.equals(pickKey)) {
            while (mc.options.keyPickItem.consumeClick()) {}
        }

        MixinMinecraftInvoker invoker = (MixinMinecraftInvoker) mc;

        if (bk.equals(pk)) {
            boolean clicked = false;
            while (KeyBindings.breakKey.consumeClick()) { clicked = true; }
            while (KeyBindings.placeKey.consumeClick()) { clicked = true; }
            if (clicked) {
                if (isMainHandPlaceable()) {
                    invoker.freecam$startUseItem();
                } else {
                    invoker.freecam$startAttack();
                }
            }
        } else {
            while (KeyBindings.breakKey.consumeClick()) {
                invoker.freecam$startAttack();
            }
            while (KeyBindings.placeKey.consumeClick()) {
                invoker.freecam$startUseItem();
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
        viewRotation.rotationYXZ(-yRot * ((float)Math.PI / 180F), xRot * ((float)Math.PI / 180F), 0.0F);
        dir.rotate(viewRotation);

        mouseRayDirection = new Vec3(dir.x(), dir.y(), dir.z());
    }

    public void onBeforeGameRendererPick() {
        gameRendererPicking = true;
    }

    public void onAfterGameRendererPick() {
        gameRendererPicking = false;

        if (active && config.rtsMode && mouseRayDirection != null && mc.level != null && mc.player != null) {
            Vec3 origin = new Vec3(x, y, z);
            Vec3 dir = mouseRayDirection;
            double range = mc.player.entityInteractionRange();
            Vec3 end = origin.add(dir.scale(range));
            AABB searchBox = new AABB(origin, end).inflate(1.0);
            EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                    mc.player, origin, end, searchBox,
                    e -> !e.isSpectator() && e.isPickable(),
                    range * range);

            if (entityHit != null) {
                double entityDist = origin.distanceToSqr(entityHit.getLocation());
                double currentDist = mc.hitResult != null ? origin.distanceToSqr(mc.hitResult.getLocation()) : Double.MAX_VALUE;
                if (entityDist < currentDist) {
                    mc.hitResult = entityHit;
                    mc.crosshairPickEntity = entityHit.getEntity();
                }
            }
        }

        if (active && config.rtsMode && mouseRayDirection != null
                && KeyBindings.forceSelect.isDown()
                && mc.hitResult != null
                && mc.level != null && mc.player != null) {
            traceThroughTargets();
        }
    }

    private void traceThroughTargets() {
        Vec3 origin = new Vec3(x, y, z);
        Vec3 dir = mouseRayDirection;
        double maxDist = 16.0;

        Vec3 searchStart;
        HitResult current = mc.hitResult;

        if (current instanceof EntityHitResult ehr) {
            searchStart = ehr.getLocation().add(dir.scale(1.0));
        } else if (current instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            Vec3 hit = bhr.getLocation();
            BlockPos bp = bhr.getBlockPos();
            double exitT = Double.MAX_VALUE;
            if (dir.x > 1e-8) exitT = Math.min(exitT, (bp.getX() + 1 - hit.x) / dir.x);
            else if (dir.x < -1e-8) exitT = Math.min(exitT, (bp.getX() - hit.x) / dir.x);
            if (dir.y > 1e-8) exitT = Math.min(exitT, (bp.getY() + 1 - hit.y) / dir.y);
            else if (dir.y < -1e-8) exitT = Math.min(exitT, (bp.getY() - hit.y) / dir.y);
            if (dir.z > 1e-8) exitT = Math.min(exitT, (bp.getZ() + 1 - hit.z) / dir.z);
            else if (dir.z < -1e-8) exitT = Math.min(exitT, (bp.getZ() - hit.z) / dir.z);
            searchStart = hit.add(dir.scale(exitT + 0.02));
        } else {
            return;
        }

        double used = origin.distanceTo(searchStart);
        if (used >= maxDist) return;
        double remaining = maxDist - used;
        Vec3 searchEnd = searchStart.add(dir.scale(remaining));

        EntityHitResult entityHit = null;
        try {
            AABB searchBox = new AABB(searchStart, searchEnd).inflate(1.0);
            entityHit = ProjectileUtil.getEntityHitResult(
                    mc.player, searchStart, searchEnd, searchBox,
                    e -> !e.isSpectator() && e.isPickable(), remaining * remaining);
        } catch (Exception ignored) {
        }

        double blockHitDist = Double.MAX_VALUE;
        BlockHitResult blockHit = null;

        boolean seenSolid = false;
        boolean passedAir = false;
        for (double t = 0; t <= remaining; t += 0.1) {
            Vec3 p = searchStart.add(dir.scale(t));
            BlockPos bp = BlockPos.containing(p.x, p.y, p.z);
            BlockState state = mc.level.getBlockState(bp);
            boolean solid = !state.isAir();
            if (solid) {
                seenSolid = true;
                if (passedAir) {
                    Vec3 clipStart = searchStart.add(dir.scale(Math.max(0, t - 1.0)));
                    BlockHitResult hit = mc.level.clip(new ClipContext(
                            clipStart, p.add(dir.scale(0.1)),
                            ClipContext.Block.OUTLINE,
                            ClipContext.Fluid.NONE,
                            mc.player));
                    if (hit.getType() == HitResult.Type.BLOCK) {
                        blockHit = hit;
                        blockHitDist = searchStart.distanceTo(hit.getLocation());
                    }
                    break;
                }
            } else {
                if (seenSolid) passedAir = true;
            }
        }

        if (blockHit == null) {
            if (current instanceof BlockHitResult firstHit && firstHit.getType() == HitResult.Type.BLOCK) {
                BlockHitResult fallback = mc.level.clip(new ClipContext(
                        searchStart, searchEnd,
                        ClipContext.Block.OUTLINE,
                        ClipContext.Fluid.NONE,
                        mc.player));
                if (fallback.getType() == HitResult.Type.BLOCK) {
                    blockHit = fallback;
                    blockHitDist = searchStart.distanceTo(fallback.getLocation());
                }
            }
        }

        if (entityHit != null) {
            double entityDist = searchStart.distanceTo(entityHit.getLocation());
            if (entityDist < blockHitDist) {
                mc.hitResult = entityHit;
                return;
            }
        }

        if (blockHit != null) {
            mc.hitResult = blockHit;
        }
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
        this.xRot = Mth.clamp(this.xRot, -MAX_PITCH, MAX_PITCH);
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