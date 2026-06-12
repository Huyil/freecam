package com.zergatul.freecam;

public class FreeCamConfig {

    public static final double MinAcceleration = 5;
    public static final double DefaultAcceleration = 50;
    public static final double MaxAcceleration = 500;
    public static final double MinMaxSpeed = 5;
    public static final double DefaultMaxSpeed = 50;
    public static final double MaxMaxSpeed = 500;
    public static final double MinSlowdownFactor = 1e-9;
    public static final double DefaultSlowdownFactor = 0.01;
    public static final double MaxSlowdownFactor = 0.5;

    public static final double MinSpeedMultiplier = 0.1;
    public static final double DefaultSpeedMultiplier = 1.0;
    public static final double MaxSpeedMultiplier = 5.0;

    public static final double MinInertia = 0.0;
    public static final double DefaultInertia = 1.0;
    public static final double MaxInertia = 1.0;

    public double acceleration;
    public double maxSpeed;
    public double slowdownFactor;
    public boolean renderHands;
    public boolean target;
    public boolean spectatorMovement;
    public boolean rememberInputState;
    public boolean showMyName;
    public boolean rtsMode;
    public double speedForward;
    public double speedStrafe;
    public double speedVertical;
    public double inertia;

    public FreeCamConfig() {
        acceleration = DefaultAcceleration;
        maxSpeed = DefaultMaxSpeed;
        slowdownFactor = DefaultSlowdownFactor;
        target = true;
        rtsMode = true;
        speedForward = DefaultSpeedMultiplier;
        speedStrafe = DefaultSpeedMultiplier;
        speedVertical = DefaultSpeedMultiplier;
        inertia = DefaultInertia;
    }

    public void clamp() {
        if (acceleration < MinAcceleration || acceleration > MaxAcceleration) {
            acceleration = DefaultAcceleration;
        }
        if (maxSpeed < MinMaxSpeed || maxSpeed > MaxMaxSpeed) {
            maxSpeed = DefaultMaxSpeed;
        }
        if (slowdownFactor < MinSlowdownFactor || slowdownFactor > MaxSlowdownFactor) {
            slowdownFactor = DefaultSlowdownFactor;
        }
    }

    public FreeCamConfig clone() {
        FreeCamConfig copy = new FreeCamConfig();
        copy.acceleration = acceleration;
        copy.maxSpeed = maxSpeed;
        copy.slowdownFactor = slowdownFactor;
        copy.renderHands = renderHands;
        copy.target = target;
        copy.spectatorMovement = spectatorMovement;
        copy.rememberInputState = rememberInputState;
        copy.showMyName = showMyName;
        copy.rtsMode = rtsMode;
        copy.speedForward = speedForward;
        copy.speedStrafe = speedStrafe;
        copy.speedVertical = speedVertical;
        copy.inertia = inertia;
        return copy;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof FreeCamConfig other) {
            return  other.acceleration == acceleration &&
                    other.maxSpeed == maxSpeed &&
                    other.slowdownFactor == slowdownFactor &&
                    other.renderHands == renderHands &&
                    other.target == target &&
                    other.spectatorMovement == spectatorMovement &&
                    other.rememberInputState == rememberInputState &&
                    other.showMyName == showMyName &&
                    other.rtsMode == rtsMode &&
                    other.speedForward == speedForward &&
                    other.speedStrafe == speedStrafe &&
                    other.speedVertical == speedVertical &&
                    other.inertia == inertia;
        } else {
            return false;
        }
    }
}