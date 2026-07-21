from bridge.tiling import TileId, block_to_tile, tile_bounds, tile_containing


def test_block_to_tile_floors_positive():
    assert block_to_tile(0, 256) == 0
    assert block_to_tile(255, 256) == 0
    assert block_to_tile(256, 256) == 1


def test_block_to_tile_floors_negative():
    assert block_to_tile(-1, 256) == -1
    assert block_to_tile(-256, 256) == -1
    assert block_to_tile(-257, 256) == -2


def test_tile_bounds_matches_tile_size():
    i1, j1, i2, j2 = tile_bounds(2, -1, 256)
    assert (i1, j1, i2, j2) == (512, -256, 768, 0)


def test_tile_containing_is_stable_across_a_whole_tile():
    a = tile_containing(10, 10, 256)
    b = tile_containing(255, 200, 256)
    assert a == b == (0, 0)


def test_cache_key_distinguishes_seed_and_scale():
    a = TileId(seed=1, tile_x=0, tile_z=0, scale=2).cache_key()
    b = TileId(seed=2, tile_x=0, tile_z=0, scale=2).cache_key()
    c = TileId(seed=1, tile_x=0, tile_z=0, scale=4).cache_key()
    assert len({a, b, c}) == 3
