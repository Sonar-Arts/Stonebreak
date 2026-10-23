package com.openmason.engine.net.protocol;

/**
 * Wire protocol version. Bumped whenever the packet layout changes incompatibly; the
 * server rejects clients whose advertised version differs from {@link #CURRENT} during
 * the handshake.
 *
 * <p>Reset to {@code 1} for the Netty rewrite — the wire format is entirely new, so the
 * legacy version counter does not carry over.
 */
public final class ProtocolVersion {

    /** Current wire protocol version. 2 = multiplayer refinement batch (keepalive,
     *  time sync, chunk meta payload, player state flags, projectile replication).
     *  3 = BlockChangeC2S carries an optional client-proposed placement state.
     *  4 = entity appearance-variant channel (EntityShearC2S, EntityVariantS2C — the
     *  handshake is exact-equality, so 3 peers can never join a 4 world and choke on
     *  an unknown packet id).
     *  5 = per-block crafting-table grids (WorkbenchSlotsC2S — issue #307).
     *  6 = server-authoritative per-world cheats flag (CheatsSetC2S, CheatsStateS2C —
     *  issue #318).
     *  7 = furnace/workbench slot intents carry a sequence number that BlockStateS2C
     *  acknowledges per recipient (issue #353).
     *  8 = server-owned inventory: InventorySyncS2C, ContainerActionC2S, HotbarSelectC2S,
     *  PlayerPrefsC2S and DeathDropC2S added; PlayerHeldItemC2S (3), PlayerDataC2S (7),
     *  FurnaceSlotsC2S (13) and WorkbenchSlotsC2S (22) retired without id reuse (issue #334).
     *  9 = server-owned progression echo (ProgressS2C — issue #334): level/xp/point pools
     *  are granted and echoed by the server only.
     *  10 = PlayerLifeC2S: death/respawn edges on the reliable channel, so the server
     *  knows which players are dead (issue #357).
     *  11 = ConsumeHeldC2S: eating debits the server-owned inventory (issue #355).
     *  12 = ChunkUnloadC2S: the client reports the chunks it drops, so the server's
     *  sent-record matches what the client holds (issue #363).
     *  13 = every inventory-predicting intent (BlockChangeC2S, DropItemC2S, SnowLayerC2S,
     *  BlockToggleC2S, ProjectileSpawnC2S, DeathDropC2S) carries a slot-intent seq that
     *  InventorySyncS2C acknowledges, plus InventoryResyncC2S for a skipped stale sync.
     *  14 = BlockBreakStartC2S: breaks are a start/finish pair the server times (issue #333).
     *  15 = chunk flow control: ChunkAckC2S (processed count + desired chunks per tick), and
     *  ChunkUnloadC2S carries the chunk the client stood in; EntityKnockbackC2S forwards ability
     *  knockback on a replicated entity. */
    public static final int CURRENT = 15;

    private ProtocolVersion() {}
}
