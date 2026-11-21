"""Neural rock shapes for Daedalus boulders (agent N, 2026-11-19).

Pipeline (each step is a module you can run with `python -m terrain_slm.rocks.<module>`):

- `sources`  : download licence-checked scanned rock meshes (Poly Haven, CC0) + a provenance manifest.
- `mesh`     : glTF reader, vertex-clustering decimation, connected components.
- `voxelize` : generalized-winding-number voxelisation (robust to scans with an open base) -> occupancy / SDF.
- `dataset`  : split scans into individual rocks, normalise, voxelise at 32^3, shape descriptors -> style labels.
- `model`    : a small conditional 3D VAE over 32^3 truncated SDFs + a latent sampler.
- `train`    : training loop with on-the-fly augmentation (rotation, anisotropic scale, chipping, erosion).
- `shapes`   : post-processing (one component, filled, flat base) + resampling to block dimensions.
- `sbrb`     : reader/writer of agent P's SBRB v1 rock bank format (`Dev Working/docs/exp2-work/P-boulders.md`).
- `bake`     : deterministic bank bake from a checkpoint + seed (novelty / plausibility filtered).
- `evaluate` : novelty (nearest-neighbour IoU), plausibility, diversity metrics + voxel preview renders.
"""
