package com.stonebreak.mobs.sbe;

/** A body that carries things on its sockets (#459): entities and the local player. */
public interface MountHost {

    /** This body's mounts (created on first use). */
    BodyMounts mounts();
}
