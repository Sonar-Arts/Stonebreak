# Training DaedalusTGM-Exp on another machine

For a collaborator with one modern NVIDIA GPU, such as an RTX 5090. Every current model trains
comfortably on one card. Measured on the full 7-region data:

| Trainer | Data on GPU | Peak VRAM | Step | Default run |
|---|---|---|---|---|
| relief sampler | 1.5 GB | 2.5 GB | ~20 ms | 30k steps ≈ 11 min |
| planner R1 | 1.5 GB | 2.4 GB | ~22 ms | 40k steps ≈ 15 min |
| refiner | 6.9 GB | 8.5 GB | ~23 ms | 30k steps ≈ 13 min |

The models are small, so a run is limited by per-step overhead rather than raw GPU speed. Run
**one training job per GPU**: two jobs on one card run about 15× slower. Each machine runs its own
experiments; a single run is not split across machines.

## 1. Get the data (from the model owner)

The owner builds the package:

```bash
# in Models/DaedalusTGM-Exp/
.venv/bin/python scripts/package_training_data.py --no-dem   # ~1.2 GB: relief sampler, planner, hydro sidecar, MaskGIT
.venv/bin/python scripts/package_training_data.py            # ~6.5 GB: also the refiner and the bank model
```

The package is `dist/DaedalusTGM-Exp-data-<date>[-cells].tar`. It contains only each region's
`meta.json`, `cells.npz` and (for the refiner and bank model) `dem.npy` + `water.npz`, plus checksums and a manifest with the
data attribution. Please don't rebuild the data yourself: that needs a 4.8 GB raw download and
climate rasters that are not in the repo.

## 2. Set up

The tested platform is Linux. WSL2 should behave the same; native Windows is untested.

```bash
git clone <repo> && cd <repo> && git checkout Project-Daedalus
cd Models/DaedalusTGM-Exp
uv sync                                            # Python 3.12 + torch cu128 (Blackwell / sm_120)
tar -xf /path/to/DaedalusTGM-Exp-data-<date>.tar   # creates data/<region>/...
sha256sum -c data/SHA256SUMS                       # every line must say OK
.venv/bin/python -m pytest -q                      # should all pass
```

With one GPU, every script defaults to `cuda:0` (`terrain_slm/device.py`); pass `--device` to override.

## 3. Train

Use a descriptive `--out` directory per experiment:

```bash
.venv/bin/python -m terrain_slm.train.train_relief  --out checkpoints/relief_r1_<you>
.venv/bin/python -m terrain_slm.train.train_planner --out checkpoints/planner_<you> --dim 176 --depth 4 --steps 40000
.venv/bin/python -m terrain_slm.train.train_refiner --out checkpoints/refiner_<you> --steps 30000   # needs the DEM package
.venv/bin/python -m terrain_slm.train.train_hydro   --out checkpoints/hydro_<you>                  # cells only, ~8 min
.venv/bin/python -m terrain_slm.train.train_banks   --out checkpoints/bank_<you>                   # DEM package (water.npz), ~6 min
.venv/bin/python -m terrain_slm.train.train_descgit --out checkpoints/descgit_<you>                # cells only, ~20 min
```

Each run writes `best.pt`, `last.pt` and `log.jsonl` to its `--out` directory. The eval lines
report held-out loss; the relief sampler also reports `amp_ratio`, where 1.0 means the sampled
valleys are as deep as real ones.

## 4. Send back

For each run, send `best.pt` and `log.jsonl`, and say which code changes the run used (a
branch, a commit or a patch). Checkpoints are a few MB. The owner evaluates them
(`python -m terrain_slm.eval.mountains`) and ships a winner as a new checkpoint directory
(see the README's "Shipping a model").

## Data attribution

- **Elevation:** produced using Copernicus WorldDEM-30 © DLR e.V. 2010-2014 and © Airbus Defence
  and Space GmbH 2014-2018, provided under COPERNICUS by the European Union and ESA.
- **Climate fields in `cells.npz`:** derived from WorldClim 2.1 (Fick & Hijmans, 2017). Keep
  them to this private research and don't redistribute them.
