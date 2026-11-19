package com.stonebreak.network;

/**
 * Stonebreak's wire protocol version. Bumped whenever the packet layout changes incompatibly; the
 * server rejects clients whose advertised version differs from {@link #CURRENT} during
 * the handshake.
 *
 * <p>Reset to {@code 1} for the Netty rewrite — the wire format is entirely new, so the
 * legacy version counter does not carry over.
 *
 * <p>Game-owned since #386: the history below is a list of Stonebreak packets. The engine's
 * {@code net} framework (codecs, registry, pipeline, limits) has no version of its own — a game
 * puts its version in its handshake packet and checks it in its handshake handler.
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
     *  PlayerStateFlags MINING bit tells the server when a player stops mining.
     *  22 = server-owned player health (issue #343): PlayerHealthS2C (health/max/dead, every
     *  player to everyone), PlayerKnockbackS2C, PvpStateS2C and PlayerDamageC2S (PvP hits) added;
     *  the state flags grow to 16 bits (FLYING, DODGING, LIFESTEAL); DeathDropC2S (27) retired —
     *  the server scatters the inventory at the death it decides; PlayerLifeC2S's respawn is a
     *  request whose spot the server picks.
     *  23 = cubic voxel runtime (#447) + 64-bit positions (#445): terrain streams as cubes. CubeDataS2C (id 1,
     *  was ChunkDataS2C) carries column records + PARTS_NETWORK cube records + payload states in one compressed
     *  blob; BlockStateNamesS2C (36) mirrors the server's block-state registry ids; CubeRequestC2S (11, was
     *  ChunkResyncRequestC2S: the client's stream asks for the cubes it misses, a request for a held cube is a
     *  resync), CubeHashesC2S (15), CubeUnloadC2S (30) and CubeAckC2S (33, cubes + KiB + rate) replace their
     *  chunk versions; ViewDistanceC2S adds the vertical render distance. Block coordinates are zigzag varlongs
     *  (BlockChangeS2C, BlockStateS2C; MultiBlockChangeS2C/BlockMetaS2C section coordinates); world positions
     *  are an int64 cell varlong + float offset per axis (NetCoords) in WelcomeS2C, EntitySpawnS2C,
     *  EntityTeleportS2C, ProjectileSpawnC2S, PlayerStateC2S/S2C, PlayerPositionS2C, PlayerJoinS2C,
     *  PlayerLifeC2S, KillCreditS2C, FishingRodC2S, SoundEventS2C, VehicleActionC2S and VehicleMoveC2S.
     *  24: WelcomeS2C carries the world's terrain scale (1:4 / 1:2) after the generator type, so the client's
     *  generator (FastLOD fallback, terrain queries) matches the server's world.
     *  25: WelcomeS2C carries the world's EXP-2 climate layout (ClimateLayout ordinal, one byte) after the terrain
     *  scale, so a DaedalusTGM-Exp client asks TGMPipe for the same layout's tiles as the server.
     *  26: WelcomeS2C carries the world's height-curve version (HeightCurveVersion number, one byte) after the
     *  climate layout: a DaedalusTGM-Exp world keeps the curve it was created with (2026-11-19 shelf band).
     *  27: the terrain scale in WelcomeS2C may be 1:1 (TerrainScalePreset.ONE_TO_ONE, ordinal 2: 15 m blocks), which
     *  an older client cannot decode - the version check refuses it cleanly instead.
     *  28: WelcomeS2C carries the world's terrain model (TerrainModelRef: folder path + release content id, two
     *  strings) after the height curve, so a DaedalusTGM-Exp client's TGMPipe runs the model the server's world was
     *  made with (review-python HIGH-1, FX1 2026-11-19). */
    public static final int CURRENT = 28;

    private ProtocolVersion() {}
}
