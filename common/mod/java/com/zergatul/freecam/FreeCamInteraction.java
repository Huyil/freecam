package com.zergatul.freecam;

import com.mojang.blaze3d.platform.InputConstants;
import com.zergatul.freecam.mixins.MixinMinecraftInvoker;
import net.minecraft.client.*;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles all RTS-mode interaction logic: mouse ray casting, entity/block
 * targeting, break/place key processing, and middle-mouse view binding.
 */
public class FreeCamInteraction {

    private final Minecraft mc = Minecraft.getInstance();
    private final FreeCam cam;

    private Vec3 mouseRayDirection;
    private boolean middleMouseDragActive;
    private double middleTotalMovement;
    private boolean middleConsumedThisHold;
    private boolean prevArrowLeft, prevArrowRight, prevArrowUp, prevArrowDown;
    private float targetYRot, targetXRot;
    private int rtsUseBoostCounter;
    private int lastBoostY = Integer.MIN_VALUE;
    private boolean insideGameRendererPick;
    private final List<KeyMapping> suppressedKeys = new ArrayList<>();

    public FreeCamInteraction(FreeCam cam) {
        this.cam = cam;
    }

    public Vec3 getMouseRayDirection() {
        return mouseRayDirection;
    }

    public boolean isMiddleMouseDragActive() {
        return middleMouseDragActive;
    }

    public float getTargetYRot() {
        return targetYRot;
    }

    public float getTargetXRot() {
        return targetXRot;
    }

    public boolean shouldReleaseCursor() {
        return cam.isActive() && cam.getConfig().rtsMode && !middleMouseDragActive;
    }

    public boolean shouldBypassMouseGrabForAttack() {
        return cam.isActive() && cam.getConfig().rtsMode;
    }

    public Vec3 getRTSPickDirection() {
        if (cam.isActive() && cam.getConfig().rtsMode && mouseRayDirection != null) {
            return mouseRayDirection;
        }
        return null;
    }

    // --- Lifecycle ---

    public void onFreecamEnabled() {
        if (cam.getConfig().rtsMode) {
            mouseRayDirection = new Vec3(0, 0, 1);
            targetYRot = cam.getYRot();
            targetXRot = cam.getXRot();
            mc.mouseHandler.releaseMouse();
        }
    }

    public void onFreecamDisabled() {
        if (cam.getConfig().rtsMode) {
            mouseRayDirection = null;
            if (middleMouseDragActive) {
                middleMouseDragActive = false;
                mc.mouseHandler.releaseMouse();
            }
            mc.mouseHandler.grabMouse();
        }
        targetYRot = 0;
        targetXRot = 0;
    }

    public void onFlyModeChanged() {
        if (cam.isActive()) {
            if (cam.getConfig().rtsMode) {
                if (mouseRayDirection == null) {
                    mouseRayDirection = new Vec3(0, 0, 1);
                }
                if (mc.screen == null) {
                    mc.mouseHandler.releaseMouse();
                }
            } else {
                mouseRayDirection = null;
                middleMouseDragActive = false;
                if (mc.screen == null) {
                    mc.mouseHandler.grabMouse();
                }
            }
        }
    }

    // --- Per-frame updates ---

    public void onRenderTickUpdate(float frameTime) {
        if (!cam.isActive() || !cam.getConfig().rtsMode || cam.isMovingAlongPath()) {
            return;
        }

        boolean canRotate = !cam.isCameraLocked() && !cam.isEyeLocked();

        if (canRotate) {
            updateArrowKeyRotation();
            updateMiddleMouseDrag();
            updateRotationLerp(frameTime);
        }

        computeMouseRayDirection();
    }

    public void onClientTickUpdate() {
        if (!cam.isActive() || !cam.getConfig().rtsMode) {
            return;
        }

        FreeCamConfig config = cam.getConfig();

        // Player view follow
        if (config.playerViewFollow && !middleMouseDragActive && !cam.isMovingAlongPath() && mc.hitResult != null && mc.player != null) {
            Vec3 playerEye = mc.player.getEyePosition();
            Vec3 target = mc.hitResult.getLocation();
            double dx = target.x - playerEye.x;
            double dy = target.y - playerEye.y;
            double dz = target.z - playerEye.z;
            double horiz = Math.sqrt(dx * dx + dz * dz);
            if (horiz > 0.001 || Math.abs(dy) > 0.001) {
                float newYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float newPitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
                newPitch = net.minecraft.util.Mth.clamp(newPitch, -85, 85);
                mc.player.setYRot(newYaw);
                mc.player.setXRot(newPitch);
                mc.player.setYHeadRot(newYaw);
            }
        }

        // Suppress conflicting keys while middle-mouse drag
        if (middleMouseDragActive || KeyBindings.viewBind.isDown()) {
            for (KeyMapping km : suppressedKeys) {
                disableKey(km);
            }
        }

        // Break/place handling
        if (!middleMouseDragActive && mc.screen == null && mc.player != null) {
            handleBreakPlaceInput();
        }

        // Right-click boost
        updateRightClickBoost();
    }

    // --- Picking hooks ---

    public void onBeforeGameRendererPick() {
        insideGameRendererPick = true;
    }

    public void onAfterGameRendererPick() {
        insideGameRendererPick = false;

        if (!cam.isActive() || !cam.getConfig().rtsMode || mouseRayDirection == null) {
            return;
        }
        if (mc.level == null || mc.player == null) {
            return;
        }

        Vec3 origin = new Vec3(cam.getX(), cam.getY(), cam.getZ());
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

        if (KeyBindings.forceSelect.isDown() && mc.hitResult != null) {
            traceThroughTargets();
        }
    }

    public boolean isInsideGameRendererPick() {
        return insideGameRendererPick;
    }

    public boolean shouldOverridePlayerEyeForPicking() {
        return cam.isActive() && (cam.getConfig().target || cam.getConfig().rtsMode) && insideGameRendererPick;
    }

    // --- Input: middle mouse for camera rotation in onPlayerTurn ---

    public boolean onPlayerTurn(double yRot, double xRot) {
        if (!cam.isActive() || cam.isCameraLocked() || cam.isFollowingCamera()) {
            return true; // vanilla handles
        }
        if (middleMouseDragActive) {
            cam.applyRotationDelta((float) yRot * 0.15F, (float) xRot * 0.15F);
            middleTotalMovement += Math.abs(yRot) + Math.abs(xRot);
            targetYRot = cam.getYRot();
            targetXRot = cam.getXRot();
            return false;
        }
        if (cam.getConfig().rtsMode) {
            return false; // consume, don't rotate camera from mouse
        }
        return true; // vanilla handles (non-RTS, non-locked)
    }

    // --- Private: rotation ---

    private void updateArrowKeyRotation() {
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
    }

    private void updateMiddleMouseDrag() {
        boolean viewBindDown = KeyBindings.viewBind.isDown();

        if (viewBindDown && !middleMouseDragActive && mc.screen == null) {
            middleMouseDragActive = true;
            middleTotalMovement = 0;
            middleConsumedThisHold = false;
            suppressedKeys.clear();
            InputConstants.Key bindKey = KeyBindings.viewBind.getKey();
            if (bindKey != InputConstants.UNKNOWN) {
                collectConflictingKeys(bindKey, suppressedKeys);
            }
            mc.mouseHandler.grabMouse();
        } else if (!viewBindDown && middleMouseDragActive) {
            middleMouseDragActive = false;
            if (mc.screen == null) {
                mc.mouseHandler.releaseMouse();
                if (middleTotalMovement < 3.0 && !middleConsumedThisHold && mc.player != null) {
                    if (isMainHandPlaceable()) {
                        handleUse();
                    } else {
                        handleAttack();
                    }
                }
            }
            suppressedKeys.clear();
        }

        if (middleMouseDragActive) {
            targetYRot = cam.getYRot();
            targetXRot = cam.getXRot();
        }
    }

    private void updateRotationLerp(float frameTime) {
        if (middleMouseDragActive) {
            // rotation set directly by mouse drag via onPlayerTurn
            return;
        }
        float lerp = 1.0f - (float)Math.exp(-frameTime * 18.0);
        float deltaY = targetYRot - cam.getYRot();
        float deltaX = targetXRot - cam.getXRot();
        if (Math.abs(deltaY) >= 0.02f || Math.abs(deltaX) >= 0.02f) {
            cam.applyRotationDelta(deltaY * lerp, deltaX * lerp);
        } else if (cam.getYRot() != targetYRot || cam.getXRot() != targetXRot) {
            cam.setYRot(targetYRot);
            cam.setXRot(targetXRot);
        }
    }

    // --- Private: attack/use ---

    private void handleAttack() {
        if (mc.hitResult instanceof EntityHitResult ehr) {
            mc.gameMode.attack(mc.player, ehr.getEntity());
            mc.player.swing(InteractionHand.MAIN_HAND);
        } else {
            ((MixinMinecraftInvoker) mc).freecam$startAttack();
        }
    }

    private void handleUse() {
        if (mc.hitResult instanceof EntityHitResult ehr) {
            Entity entity = ehr.getEntity();
            InteractionResult result = mc.gameMode.interactAt(mc.player, entity, ehr, InteractionHand.MAIN_HAND);
            if (!result.consumesAction()) {
                result = mc.gameMode.interact(mc.player, entity, InteractionHand.MAIN_HAND);
            }
            if (result.shouldSwing()) {
                mc.player.swing(InteractionHand.MAIN_HAND);
            }
        } else {
            ((MixinMinecraftInvoker) mc).freecam$startUseItem();
        }
    }

    private void handleBreakPlaceInput() {
        InputConstants.Key bk = KeyBindings.breakKey.getKey();
        InputConstants.Key pk = KeyBindings.placeKey.getKey();
        InputConstants.Key attackKey = mc.options.keyAttack.getKey();
        InputConstants.Key useKey = mc.options.keyUse.getKey();
        InputConstants.Key pickKey = mc.options.keyPickItem.getKey();

        // Suppress vanilla handling when keys overlap
        if (bk.equals(attackKey)) {
            while (mc.options.keyAttack.consumeClick()) {}
        }
        if (pk.equals(useKey)) {
            while (mc.options.keyUse.consumeClick()) {}
        }
        if (bk.equals(pickKey) || pk.equals(pickKey)) {
            while (mc.options.keyPickItem.consumeClick()) {}
        }

        if (bk.equals(pk)) {
            boolean clicked = false;
            while (KeyBindings.breakKey.consumeClick()) { clicked = true; }
            while (KeyBindings.placeKey.consumeClick()) { clicked = true; }
            if (clicked) {
                if (isMainHandPlaceable()) {
                    handleUse();
                } else {
                    handleAttack();
                }
            }
        } else {
            while (KeyBindings.breakKey.consumeClick()) {
                handleAttack();
            }
            while (KeyBindings.placeKey.consumeClick()) {
                handleUse();
            }
        }
    }

    private void updateRightClickBoost() {
        if (!middleMouseDragActive && KeyBindings.placeKey.isDown()) {
            int currentY = Integer.MIN_VALUE;
            if (mc.hitResult instanceof BlockHitResult bhr) {
                currentY = bhr.getBlockPos().getY();
            }
            if (currentY == lastBoostY) {
                rtsUseBoostCounter++;
                if (rtsUseBoostCounter >= 2) {
                    rtsUseBoostCounter = 0;
                    handleUse();
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

    // --- Private: mouse ray ---

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
        Quaternionf viewRotation = new Quaternionf(0.0F, 0.0F, 0.0F, 1.0F);
        viewRotation.rotationYXZ(-cam.getYRot() * ((float)Math.PI / 180F), cam.getXRot() * ((float)Math.PI / 180F), 0.0F);
        dir.rotate(viewRotation);

        mouseRayDirection = new Vec3(dir.x(), dir.y(), dir.z());
    }

    // --- Private: trace through targets (Ctrl force-select) ---

    private void traceThroughTargets() {
        Vec3 origin = new Vec3(cam.getX(), cam.getY(), cam.getZ());
        Vec3 dir = mouseRayDirection;
        double maxDist = 16.0;

        Vec3 searchStart;
        HitResult current = mc.hitResult;

        if (current instanceof EntityHitResult ehr) {
            searchStart = ehr.getLocation().add(dir.scale(1.0));
        } else if (current instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            Vec3 hit = bhr.getLocation();
            net.minecraft.core.BlockPos bp = bhr.getBlockPos();
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
            net.minecraft.core.BlockPos bp = net.minecraft.core.BlockPos.containing(p.x, p.y, p.z);
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

    // --- Private: key conflict ---

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

    // --- Private: utility ---

    private boolean isMainHandPlaceable() {
        ItemStack mainHand = mc.player.getMainHandItem();
        return !mainHand.isEmpty() && mainHand.getItem() instanceof BlockItem;
    }

    private void disableKey(KeyMapping key) {
        while (key.consumeClick()) {}
        key.setDown(false);
    }
}
