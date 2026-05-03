package com.stonebreak.network.sync;

/**
 * Operating mode of the local node within a sync session.
 */
public enum SyncMode {
    OFFLINE,
    HOST,    // authoritative; mutates state directly, broadcasts to clients
    CLIENT   // shadow; sends local intents to host, applies host broadcasts
}
