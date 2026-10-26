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
     *  knockback on a replicated entity.
     *  16 = client/server sync pass: PlayerPrefsC2S carries a provisional (respec window) flag
     *  (#421); HandshakeC2S/WelcomeS2C carry a per-client join secret (#335); EntityDamageC2S a hit
     *  kind and KillCreditS2C the target/source/hit kind, plus EntityStatusEffectC2S and
     *  EntityStatusS2C (status effects and health fraction on shadows, #339/#371/#374);
     *  GiveItemS2C/DropItemC2S and item-drop spawn metadata carry the stack state (#342);
     *  FishingRodC2S and DecoyCastC2S, with bobber/decoy spawn metadata (#341).
     *  17 = PlayerPositionS2C: the server puts a player back after refusing a move further than
     *  it could have travelled (issue #336); serverbound frames are capped at 256 KiB.
     *  18 = weather zones (WeatherSyncS2C carries them, WeatherSetC2S a world/local scope);
     *  EntityDamageC2S carries a damage type and mob invulnerability is keyed per melee weapon;
     *  KillCreditS2C carries the damage-number anchor.
     *  19 = vehicles: VehicleActionC2S (place/board/dismount/hit) and VehicleMoveC2S (the rower's
     *  boat position and oar input); the DINGHY entity type replicates.
     *  20 = server cost pass (issue #346): HandshakeC2S lists the compression codecs the client
     *  decodes, ChunkDataS2C blobs carry a codec byte (raw / zstd / deflate), and EntityAnimS2C
     *  sends known animation states as a one-byte code.
     *  21 = sound replication (issue #344): SoundEventS2C plays another player's block
     *  break/place/mining hits and mobs' hurt/death voices on nearby clients, and the
     *  PlayerStateFlags MINING bit tells the server when a player stops mining. */
    public static final int CURRENT = 21;

    private ProtocolVersion() {}
}
