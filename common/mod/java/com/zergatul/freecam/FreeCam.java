package com.zergatul.freecam;

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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
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
    private final static float MAX_PITCH = 85.0f;

    private final Minecraft mc = Minecraft.getInstance();
    private final Quaternionf rotation = new Quaternionf(0.0F, 0.0F, 0.0F, 1.0F);
    private final Vector3f forwards = new Vector3f(0.0F, 0.0F, 1.0F);
    private final Vector3f up = new Vector3f(0.0F, 1.0F, 0.0F);
    private final Vector3f left = new Vector3f(1.0F, 0.0F, 0.0F);
    private final FreeCamPath path = new FreeCamPath(this);
    private final FreeCamConfig config = ConfigRepository.instance.load();
    private final FreeCamInteraction interaction = new FreeCamInteraction(this);

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
    private boolean insideFreecamCustomPick;
    private boolean cameraLock;
    private boolean eyeLock;
    private boolean followCamera;
    private double followDeltaX, followDeltaY, followDeltaZ;
    private boolean moveAlongPath;
    private long pathStartTime;
    private long dontMoveFreeCamBefore;

    private FreeCam() {

    }

    // --- Getters ---

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

    public FreeCamInteraction getInteraction() {
        return interaction;
    }

    boolean isCameraLocked() {
        return cameraLock;
    }

    boolean isEyeLocked() {
        return eyeLock;
    }

    boolean isFollowingCamera() {
        return followCamera;
    }

    boolean isMovingAlongPath() {
        return moveAlongPath;
    }

    // --- Setters for interaction ---

    void setYRot(float yRot) {
        this.yRot = yRot;
        calculateVectors();
    }

    void setXRot(float xRot) {
        this.xRot = xRot;
        calculateVectors();
    }

    void applyRotationDelta(float deltaYRot, float deltaXRot) {
        this.yRot += deltaYRot;
        this.xRot += deltaXRot;
        this.xRot = Mth.clamp(this.xRot, -90, 90);
        calculateVectors();
    }

    // --- Lifecycle ---

    public void toggleFreecam() {
        if (active) {
            disableFreecam();
        } else {
            enableFreecam();
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

    public void enableFreecam() {
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

        interaction.onFreecamEnabled();
    }

    public void disableFreecam() {
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

        interaction.onFreecamDisabled();
    }

    // --- Key bindings ---

    public void onHandleKeyBindings() {
        if (mc.player == null || mc.screen != null) {
            return;
        }
        while (KeyBindings.toggleFreeCam.consumeClick()) {
            toggleFreecam();
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

    // --- Mouse turn ---

    public boolean onPlayerTurn(double yRot, double xRot) {
        if (!active || cameraLock || followCamera) {
            return true;
        }

        // Let interaction handle RTS mode first
        if (config.rtsMode) {
            return interaction.onPlayerTurn(yRot, xRot);
        }

        // Non-RTS, non-locked: apply rotation directly
        if (!eyeLock && !moveAlongPath) {
            applyRotationDelta((float) yRot * 0.15F, (float) xRot * 0.15F);
        }
        return false;
    }

    // --- Rendering queries ---

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

    // --- Render tick: camera movement ---

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
            // interaction target tracking uses these
        }

        // Let interaction handle RTS-specific per-frame logic
        interaction.onRenderTickUpdate(frameTime);
    }

    // --- Client tick ---

    public void onClientTickStart() {
        if (active) {
            disableKey(mc.options.keyTogglePerspective);
            interaction.onClientTickUpdate();
            playerInput.tick(false, 0);
        }
    }

    public void onWorldUnload() {
        disableFreecam();
    }

    // --- Render world last: highlights ---

    public void onRenderWorldLast(Matrix4f pose, Matrix4f projectionMatrix, Camera camera) {
        if (!active) {
            return;
        }

        Vec3 cam = camera.getPosition();

        if (!interaction.isMiddleMouseDragActive() && config.highlightOpacity > 0.001) {
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

    // --- Highlight rendering ---

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

        boolean ctrl = active && config.rtsMode && KeyBindings.forceSelect.isDown();
        float r = ctrl ? 1.0f : 1.0f;
        float g = ctrl ? 0.3f : 0.5f;
        float b = ctrl ? 1.0f : 0.0f;
        float a = opacity;

        // Semi-transparent filled faces
        Tesselator tesselator = Tesselator.getInstance();
        float fa = a * 0.15f;
        BufferBuilder fillBB = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        // bottom (y0)
        fillBB.addVertex(x0,y0,z0).setColor(r,g,b,fa); fillBB.addVertex(x1,y0,z0).setColor(r,g,b,fa);
        fillBB.addVertex(x1,y0,z1).setColor(r,g,b,fa); fillBB.addVertex(x0,y0,z1).setColor(r,g,b,fa);
        // top (y1)
        fillBB.addVertex(x0,y1,z0).setColor(r,g,b,fa); fillBB.addVertex(x1,y1,z0).setColor(r,g,b,fa);
        fillBB.addVertex(x1,y1,z1).setColor(r,g,b,fa); fillBB.addVertex(x0,y1,z1).setColor(r,g,b,fa);
        // front (z1)
        fillBB.addVertex(x0,y0,z1).setColor(r,g,b,fa); fillBB.addVertex(x1,y0,z1).setColor(r,g,b,fa);
        fillBB.addVertex(x1,y1,z1).setColor(r,g,b,fa); fillBB.addVertex(x0,y1,z1).setColor(r,g,b,fa);
        // back (z0)
        fillBB.addVertex(x0,y0,z0).setColor(r,g,b,fa); fillBB.addVertex(x1,y0,z0).setColor(r,g,b,fa);
        fillBB.addVertex(x1,y1,z0).setColor(r,g,b,fa); fillBB.addVertex(x0,y1,z0).setColor(r,g,b,fa);
        // left (x0)
        fillBB.addVertex(x0,y0,z0).setColor(r,g,b,fa); fillBB.addVertex(x0,y0,z1).setColor(r,g,b,fa);
        fillBB.addVertex(x0,y1,z1).setColor(r,g,b,fa); fillBB.addVertex(x0,y1,z0).setColor(r,g,b,fa);
        // right (x1)
        fillBB.addVertex(x1,y0,z0).setColor(r,g,b,fa); fillBB.addVertex(x1,y0,z1).setColor(r,g,b,fa);
        fillBB.addVertex(x1,y1,z1).setColor(r,g,b,fa); fillBB.addVertex(x1,y1,z0).setColor(r,g,b,fa);
        renderQuads(fillBB, pose, projection);

        // Wireframe edges
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
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

    // --- Picking hooks ---

    public boolean shouldOverridePlayerEyeForPicking(Entity entity) {
        if (active && (config.target || config.rtsMode)) {
            return entity == mc.getCameraEntity() && interaction.shouldOverridePlayerEyeForPicking() || insideFreecamCustomPick;
        } else {
            return false;
        }
    }

    public void onBeforeGameRendererPick() {
        interaction.onBeforeGameRendererPick();
    }

    public void onAfterGameRendererPick() {
        interaction.onAfterGameRendererPick();
    }

    public Vec3 getRTSPickDirection() {
        return interaction.getRTSPickDirection();
    }

    public boolean shouldReleaseCursor() {
        return interaction.shouldReleaseCursor();
    }

    public boolean shouldBypassMouseGrabForAttack() {
        return interaction.shouldBypassMouseGrabForAttack();
    }

    public void onFlyModeChanged() {
        interaction.onFlyModeChanged();
    }

    // --- Debug screen ---

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

        insideFreecamCustomPick = true;
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
            insideFreecamCustomPick = false;
        }
    }

    // --- Path ---

    private void startPath() {
        if (!active) {
            return;
        }

        moveAlongPath = true;
        pathStartTime = System.nanoTime();
    }

    // --- Camera math ---

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

    private void disableKey(net.minecraft.client.KeyMapping key) {
        while (key.consumeClick()) {}
        key.setDown(false);
    }

    // --- Rendering helpers ---

    private void renderQuads(BufferBuilder bufferBuilder, Matrix4f pose, Matrix4f projection) {
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        SharedVertexBuffer.instance.bind();
        SharedVertexBuffer.instance.upload(bufferBuilder.buildOrThrow());
        SharedVertexBuffer.instance.drawWithShader(pose, projection, GameRenderer.getPositionColorShader());
        VertexBuffer.unbind();

        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
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
