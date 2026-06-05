package com.github.epsilon.events.impl;

public class FallFlyingEvent {

    private float pitch;

    public FallFlyingEvent(float pitch) {
        this.pitch = pitch;
    }

    public void setPitch(float pitch) {
        this.pitch = pitch;
    }

    public float getPitch() {
        return this.pitch;
    }

}
