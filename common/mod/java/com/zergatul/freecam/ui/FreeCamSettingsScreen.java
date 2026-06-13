package com.zergatul.freecam.ui;

import com.zergatul.freecam.ConfigRepository;
import com.zergatul.freecam.FreeCam;
import com.zergatul.freecam.FreeCamConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.text.DecimalFormat;

public class FreeCamSettingsScreen extends Screen {

    private static final Component TITLE = Component.translatable("options.freecam.settings.title");
    private static final Component ACCELERATION = Component.translatable("options.freecam.settings.acceleration");
    private static final Component ACCELERATION_TOOLTIP = Component.translatable("options.freecam.settings.acceleration.tooltip");
    private static final Component MAX_SPEED = Component.translatable("options.freecam.settings.maxspeed");
    private static final Component MAX_SPEED_TOOLTIP = Component.translatable("options.freecam.settings.maxspeed.tooltip");
    private static final Component SLOWDOWN = Component.translatable("options.freecam.settings.slowdown");
    private static final Component TARGET = Component.translatable("options.freecam.settings.target");
    private static final Component TARGET_TOOLTIP = Component.translatable("options.freecam.settings.target.tooltip");
    private static final Component HANDS = Component.translatable("options.freecam.settings.hands");
    private static final Component HANDS_TOOLTIP = Component.translatable("options.freecam.settings.hands.tooltip");
    private static final Component INPUT = Component.translatable("options.freecam.settings.remember.input");
    private static final Component INPUT_TOOLTIP = Component.translatable("options.freecam.settings.remember.input.tooltip");
    private static final Component FLY_MODE = Component.translatable("options.freecam.settings.flymode");
    private static final Component FLY_MODE_CREATIVE = Component.translatable("options.freecam.settings.flymode.creative");
    private static final Component FLY_MODE_RTS = Component.translatable("options.freecam.settings.flymode.rts");
    private static final Component FLY_MODE_SPECTATOR = Component.translatable("options.freecam.settings.flymode.spectator");
    private static final Component SHOW_MY_NAME = Component.translatable("options.freecam.settings.show.name");
    private static final Component SHOW_MY_NAME_TOOLTIP = Component.translatable("options.freecam.settings.show.name.tooltip");
    private static final Component SPEED_FORWARD = Component.translatable("options.freecam.settings.speed.forward");
    private static final Component SPEED_STRAFE = Component.translatable("options.freecam.settings.speed.strafe");
    private static final Component SPEED_VERTICAL = Component.translatable("options.freecam.settings.speed.vertical");
    private static final Component INERTIA = Component.translatable("options.freecam.settings.inertia");
    private static final Component INERTIA_TOOLTIP = Component.translatable("options.freecam.settings.inertia.tooltip");
    private static final Component VIEW_FOLLOW = Component.translatable("options.freecam.settings.viewfollow");
    private static final Component VIEW_FOLLOW_TOOLTIP = Component.translatable("options.freecam.settings.viewfollow.tooltip");
    private static final Component HIGHLIGHT = Component.translatable("options.freecam.settings.highlight");
    private static final Component HIGHLIGHT_TOOLTIP = Component.translatable("options.freecam.settings.highlight.tooltip");
    private static final int BUTTON_WIDTH = 150;
    private static final int BUTTON_HEIGHT = 20;
    private static final int DONE_BUTTON_WIDTH = 200;
    private static final int GAP = 12;
    private static final int TITLE_TOP = 20;
    private static final int BUTTONS_TOP = 40;
    private static final int LINE_HEIGHT = BUTTON_HEIGHT + GAP / 2;

    private final Screen previous;
    private final FreeCamConfig originalConfig;

    public FreeCamSettingsScreen() {
        this(null);
    }

    public FreeCamSettingsScreen(Screen previous) {
        super(TITLE);
        this.previous = previous;
        this.originalConfig = FreeCam.instance.getConfig().clone();
    }

    @Override
    protected void init() {
        super.init();

        int column1 = (this.width - GAP) / 2 - BUTTON_WIDTH;
        int column2 = (this.width + GAP) / 2;

        addRenderableWidget(new StringWidget(
                this.width / 2 - this.font.width(TITLE) / 2, TITLE_TOP,
                this.font.width(TITLE), this.font.lineHeight,
                TITLE, this.font));

        int y = BUTTONS_TOP;

        addSpeedSlider(column1, y, SPEED_FORWARD, config -> config.speedForward, (cfg, v) -> cfg.speedForward = v);
        addExpSlider(column2, y, MAX_SPEED, MAX_SPEED_TOOLTIP, config -> config.maxSpeed, (cfg, v) -> cfg.maxSpeed = v, FreeCamConfig.MinMaxSpeed, FreeCamConfig.DefaultMaxSpeed, FreeCamConfig.MaxMaxSpeed);

        y += LINE_HEIGHT;
        addSpeedSlider(column1, y, SPEED_STRAFE, config -> config.speedStrafe, (cfg, v) -> cfg.speedStrafe = v);
        addExpSlider(column2, y, ACCELERATION, ACCELERATION_TOOLTIP, config -> config.acceleration, (cfg, v) -> cfg.acceleration = v, FreeCamConfig.MinAcceleration, FreeCamConfig.DefaultAcceleration, FreeCamConfig.MaxAcceleration);

        y += LINE_HEIGHT;
        addSpeedSlider(column1, y, SPEED_VERTICAL, config -> config.speedVertical, (cfg, v) -> cfg.speedVertical = v);
        addSlowdownSlider(column2, y);

        y += LINE_HEIGHT;
        addInertiaSlider(column1, y);
        addFlyModeButton(column2, y);

        y += LINE_HEIGHT;
        addRenderableWidget(CycleButton.onOffBuilder()
                .withInitialValue(FreeCam.instance.getConfig().renderHands)
                .withTooltip(value -> Tooltip.create(HANDS_TOOLTIP))
                .create(column1, y, BUTTON_WIDTH, BUTTON_HEIGHT, HANDS, (button, value) -> {
                    FreeCam.instance.getConfig().renderHands = value;
                }));
        addRenderableWidget(CycleButton.onOffBuilder()
                .withInitialValue(FreeCam.instance.getConfig().target)
                .withTooltip(value -> Tooltip.create(TARGET_TOOLTIP))
                .create(column2, y, BUTTON_WIDTH, BUTTON_HEIGHT, TARGET, (button, value) -> {
                    FreeCam.instance.getConfig().target = value;
                }));

        y += LINE_HEIGHT;
        addRenderableWidget(CycleButton.onOffBuilder()
                .withInitialValue(FreeCam.instance.getConfig().rememberInputState)
                .withTooltip(value -> Tooltip.create(INPUT_TOOLTIP))
                .create(column1, y, BUTTON_WIDTH, BUTTON_HEIGHT, INPUT, (button, value) -> {
                    FreeCam.instance.getConfig().rememberInputState = value;
                }));
        addRenderableWidget(CycleButton.onOffBuilder()
                .withInitialValue(FreeCam.instance.getConfig().showMyName)
                .withTooltip(value -> Tooltip.create(SHOW_MY_NAME_TOOLTIP))
                .create(column2, y, BUTTON_WIDTH, BUTTON_HEIGHT, SHOW_MY_NAME, (button, value) -> {
                    FreeCam.instance.getConfig().showMyName = value;
                }));

        y += LINE_HEIGHT;
        addRenderableWidget(CycleButton.onOffBuilder()
                .withInitialValue(FreeCam.instance.getConfig().playerViewFollow)
                .withTooltip(value -> Tooltip.create(VIEW_FOLLOW_TOOLTIP))
                .create(column1, y, BUTTON_WIDTH, BUTTON_HEIGHT, VIEW_FOLLOW, (button, value) -> {
                    FreeCam.instance.getConfig().playerViewFollow = value;
                }));
        addRenderableWidget(new SliderButton.Builder()
                .position(column2, y)
                .size(BUTTON_WIDTH, BUTTON_HEIGHT)
                .message(HIGHLIGHT)
                .tooltip(Tooltip.create(HIGHLIGHT_TOOLTIP))
                .mapper(new LinearValueMapper(0.0, 1.0) {
                    @Override
                    public String toDisplay(double value) {
                        return Integer.toString((int) Math.round(toSettingValue(value) * 100)) + "%";
                    }
                })
                .setter((button, value) -> FreeCam.instance.getConfig().highlightOpacity = value)
                .value(FreeCam.instance.getConfig().highlightOpacity)
                .create());

        y += 2 * LINE_HEIGHT;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
                .pos((width - DONE_BUTTON_WIDTH) / 2, y)
                .size(DONE_BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
    }

    private void addSpeedSlider(int x, int y, Component label,
                                java.util.function.Function<FreeCamConfig, Double> getter,
                                java.util.function.BiConsumer<FreeCamConfig, Double> setter) {
        addRenderableWidget(new SliderButton.Builder()
                .position(x, y)
                .size(BUTTON_WIDTH, BUTTON_HEIGHT)
                .message(label)
                .mapper(new LinearValueMapper(FreeCamConfig.MinSpeedMultiplier, FreeCamConfig.MaxSpeedMultiplier))
                .setter((button, value) -> setter.accept(FreeCam.instance.getConfig(), value))
                .value(getter.apply(FreeCam.instance.getConfig()))
                .create());
    }

    private void addExpSlider(int x, int y, Component label, Component tooltip,
                              java.util.function.Function<FreeCamConfig, Double> getter,
                              java.util.function.BiConsumer<FreeCamConfig, Double> setter,
                              double min, double mid, double max) {
        addRenderableWidget(new SliderButton.Builder()
                .position(x, y)
                .size(BUTTON_WIDTH, BUTTON_HEIGHT)
                .message(label)
                .tooltip(Tooltip.create(tooltip))
                .mapper(new ExponentialValueMapper(min, mid, max) {
                    @Override
                    public String toDisplay(double value) {
                        return String.format("%.1f", toSettingValue(value));
                    }
                })
                .setter((button, value) -> setter.accept(FreeCam.instance.getConfig(), value))
                .value(getter.apply(FreeCam.instance.getConfig()))
                .create());
    }

    private void addSlowdownSlider(int x, int y) {
        addRenderableWidget(new SliderButton.Builder()
                .position(x, y)
                .size(BUTTON_WIDTH, BUTTON_HEIGHT)
                .message(SLOWDOWN)
                .tooltipProvider((value, mapper) -> {
                    double factor = mapper.toSettingValue(value);
                    String str;
                    if (factor < 0.01) {
                        str = new DecimalFormat("0.###E0").format(factor);
                    } else {
                        str = new DecimalFormat("0.000").format(factor);
                    }
                    return Tooltip.create(Component.translatable("options.freecam.settings.slowdown.tooltip", str));
                })
                .mapper(new ExponentialValueMapper(FreeCamConfig.MaxSlowdownFactor, FreeCamConfig.DefaultSlowdownFactor, FreeCamConfig.MinSlowdownFactor) {
                    @Override
                    public String toDisplay(double value) {
                        return Integer.toString((int) Math.round(value * 100));
                    }
                })
                .setter((button, value) -> FreeCam.instance.getConfig().slowdownFactor = value)
                .value(FreeCam.instance.getConfig().slowdownFactor)
                .create());
    }

    private void addInertiaSlider(int x, int y) {
        addRenderableWidget(new SliderButton.Builder()
                .position(x, y)
                .size(BUTTON_WIDTH, BUTTON_HEIGHT)
                .message(INERTIA)
                .tooltip(Tooltip.create(INERTIA_TOOLTIP))
                .mapper(new LinearValueMapper(FreeCamConfig.MinInertia, FreeCamConfig.MaxInertia) {
                    @Override
                    public String toDisplay(double value) {
                        return Integer.toString((int) Math.round(toSettingValue(value) * 100)) + "%";
                    }
                })
                .setter((button, value) -> FreeCam.instance.getConfig().inertia = value)
                .value(FreeCam.instance.getConfig().inertia)
                .create());
    }

    private void addFlyModeButton(int x, int y) {
        FreeCamConfig config = FreeCam.instance.getConfig();
        int initialMode;
        if (config.rtsMode) {
            initialMode = 1;
        } else if (config.spectatorMovement) {
            initialMode = 2;
        } else {
            initialMode = 0;
        }
        addRenderableWidget(new CycleButton.Builder<Integer>(mode -> switch (mode) {
            case 1 -> FLY_MODE_RTS;
            case 2 -> FLY_MODE_SPECTATOR;
            default -> FLY_MODE_CREATIVE;
        })
                .withValues(0, 1, 2)
                .withInitialValue(initialMode)
                .create(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, FLY_MODE, (button, value) -> {
                    FreeCamConfig cfg = FreeCam.instance.getConfig();
                    cfg.rtsMode = (value == 1);
                    cfg.spectatorMovement = (value == 2);
                    FreeCam.instance.onFlyModeChanged();
                }));
    }

    @Override
    public void onClose() {
        if (!originalConfig.equals(FreeCam.instance.getConfig())) {
            ConfigRepository.instance.save(FreeCam.instance.getConfig());
        }

        if (previous != null) {
            Minecraft.getInstance().setScreen(previous);
        } else {
            super.onClose();
        }
    }
}
