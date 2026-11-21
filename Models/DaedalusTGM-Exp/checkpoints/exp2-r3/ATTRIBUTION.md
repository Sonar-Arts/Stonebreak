# DaedalusTGM-Exp-2: data attribution

The DaedalusTGM-Exp-2 terrain model that ships with Stonebreak was trained on the datasets below. The game contains
only the trained model weights. None of these datasets is redistributed with it. Each licensor's required notice
is given verbatim. The citations follow each provider's own "how to cite" text.

- **Dataset licences** (read from each primary source, 2026-11-18): `Dev Working/docs/DaedalusTGM-Exp-2-Data-Licences.md` (local).
- **Checkpoint audit** (every shipped weight traced to its training data and its whole init chain, 2026-11-19):
  `Dev Working/docs/exp2-work/H-ship-readiness.md` (local); the machine-readable record is
  `scripts/release_provenance.json`, which `scripts/make_release.py` checks before it builds a release and copies
  into the release's `release.json`.

**Release covered:** `checkpoints/exp2-r1` (built from `checkpoints/exp2/world_exp2b`; content id `ceafecb2d1cf`).
A later release must be re-checked: `scripts/make_release.py <src> <out> --check-only`.

> **Rule:** only public-domain, CC0, CC BY or open-with-attribution data. No WorldClim (non-commercial), FABDEM
> (non-commercial), Copernicus EEA-10 (restricted), and no CHELSA-TraCE21k until WSL confirms its licence in writing.
> A model initialised or distilled from a model trained on any of these counts as trained on it.

---

## What each shipped checkpoint was trained on (exp2-r1)

"data v2" = `/mnt/extrastorage/terrain-slm-data/v2-gedtm30` (49 regions): land GEDTM30 (GLO-30 only where GEDTM30
has no value: water surfaces), sea floor GEBCO 2024, Hawaii and Puerto Rico sea floors refined with NOAA CUDEM;
climate per cell from CHELSA v2.1 + ERA5 (WeatherBench 2); drainage weighted by that precipitation. No WorldClim:
`terrain_slm/data/build.py` (EXP-1's builder, the only WorldClim reader) is not part of the v2 pipeline.

| File | Training run | Initialised from | Data | Sources |
|---|---|---|---|---|
| planner.pt | exp2/planner_v4b | planner_v4a <- planner_v3 (from scratch) | data v2 | GEDTM30, GLO-30, GEBCO, CUDEM, CHELSA v2.1, ERA5 |
| relief.pt | exp2/relief_v2c | relief_v2b <- relief_v2 (from scratch) | data v2 | same |
| detail.pt | exp2/d2_full_v2_mf_experts | merge of d2_full_v2_mf + d2_full_v2_experts, both from d2_full_v2 (from scratch) | data v2 | same |
| refiner.pt | exp2/refiner_v2 | from scratch | data v2 | same |
| hydro.pt | exp2/hydro_v2 | from scratch | data v2 | same |
| bank.pt | exp2/bank_v2 | from scratch | data v2 (DEM + water mask only) | same |
| biome.pt | exp2/biome_v2 | from scratch | data v2 (labels: the rule classifier on v2 climate) | same |
| sr30.pt | exp2/sr30_r0 | from scratch | data v2 30 m DEMs | same |
| climate.pt | exp2/climate_r1 | from scratch | GEBCO 0.25° geography, ERA5 wind; targets CHELSA v2.1 + ERA5 soil/snow | GEBCO, CHELSA v2.1, ERA5 |

- **Not used by exp2-r1:** CHELSA-TraCE21k (climate_r1 was saved at 16:01 on 2026-11-18, before the first TraCE file
  was downloaded at 16:19, and was trained without `--paleo`), WorldClim, FABDEM, EEA-10, and every EXP-1 (v2/v3/v4)
  checkpoint (no EXP-2 run starts from one).
- **Cleared candidate, not in exp2-r1:** `exp2/sr15_r0` (SR-15 for the 1:1 preset): USGS 3DEP only. Add the 3DEP
  line to the credits when it ships. `exp2/qad_d2_mf_experts` (FP8 student, not shipped) is also clean (data v2,
  teacher = detail.pt).
- **Tainted, never ship:** the small A/B proxies `exp2/ab_v4_small`, `ab_d2_small`, `mf_d2_small`, `qad_d2_small`
  (trained with the trainers' default `--data-dir`, which is EXP-1 data with WorldClim climate), `exp2/climate_r2..r4`
  (paleo experiments, counted as TraCE-trained), and every EXP-1 checkpoint.
- **EXP-1 removed (2026-11-19):** the WorldClim-trained EXP-1 checkpoints that used to be committed (`checkpoints/v2`,
  `v3`, `v4` and the training runs `bank_b0`, `descgit_d0`, `hydro_h0`, `planner_r0*`, `planner_r1*`, `poc`,
  `poc_prev`, `r0m`, `refiner_r0*`, `relief_r0`: 48 `.pt` files) are no longer in the checkout and the game has no
  fallback to them. They remain in the git history of earlier commits until the history is rewritten (a separate
  decision). Only those runs' training logs (`log.jsonl`, `train.out`, no weights) are still tracked. Other EXP-1 run
  folders on the training machine are local-only (gitignored) and never ship.
- **Distribution:** the release's weights (`checkpoints/<release>/*.pt`) are stored with Git LFS; `release.json` and
  this file are normal files in the same folder.

---

## Credits (for the in-game credits screen)

**Terrain model training data**

- **Copernicus DEM GLO-30**: produced using Copernicus WorldDEM-30 © DLR e.V. 2010-2014 and © Airbus Defence and
  Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved.
- **GEDTM30**: Global Ensemble Digital Terrain Model 30m, by Yufeng Ho and Tomislav Hengl, OpenGeoHub Foundation,
  licensed under CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/).
- **GEBCO**: GEBCO Compilation Group (2024) GEBCO 2024 Grid (doi:10.5285/1c44ce99-0a0d-5f4f-e063-7086abc0ea0f).
- **NOAA NCEI CUDEM**: Continuously Updated Digital Elevation Model, 1/9 arc-second topo-bathymetry (Hawaii, Puerto
  Rico), CIRES / NOAA National Centers for Environmental Information.
- **ERA5**: Generated using or contains modified Copernicus Climate Change Service information 2026, licensed under
  CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/). ERA5 accessed via WeatherBench 2 (Google Research).
- **CHELSA v2.1**: Climatologies at high resolution for the earth's land surface areas (Karger et al.), and
  CHELSA-BIOCLIM+ (Brun et al.), WSL.
- *(only once the vegetation node ships)* **ETH Global Canopy Height 2020** (Lang et al. 2023, ETH Zurich), CC BY 4.0;
  **ESA WorldCover**: (c) ESA WorldCover project 2021 / Contains modified Copernicus Sentinel data (2021) processed
  by ESA WorldCover consortium.
- *(only once the SR-15 / 1:1 node ships)* **USGS 3DEP**: Map services and data available from U.S. Geological
  Survey, National Geospatial Program.
- *(rock shape bank, courtesy credit - CC0 needs none)* **Poly Haven**: boulder shapes learned from Poly Haven 3D rock
  scans (polyhaven.com, CC0).

Stonebreak and DaedalusTGM-Exp-2 are not endorsed by Airbus, DLR, ESA, the European Union, ECMWF, the European
Commission, GEBCO, the IHO, the IOC, NOAA, the USGS, WSL, OpenGeoHub, ETH Zurich or any other data provider.

---

## Legal notice (for the EULA / third-party notices file)

These sentences must go in the licence or legal notice that covers distribution of the game, not only in the
credits:

> The organisations in charge of the Copernicus programme by law or by delegation do not incur any liability for
> any use of the Copernicus WorldDEM-30.

> Contains modified Copernicus Climate Change Service information 2026. Neither the European Commission nor ECMWF
> is responsible for any use that may be made of the Copernicus information or data it contains.

> The GEBCO Grid, NOAA CUDEM and USGS 3DEP data were used only to train a terrain model. The game is not a
> navigation product. Terrain it produces must not be used for navigation or any purpose involving safety.

---

## Full citations

### Copernicus DEM GLO-30 (Copernicus WorldDEM-30)

- **Licence:** Licence for the use of the Copernicus WorldDEM-30 (free, worldwide; adaptation permitted).
  https://dataspace.copernicus.eu/explore-data/data-collections/copernicus-contributing-missions/collections-description/COP-DEM
- **Required notice (adapted data):** "produced using Copernicus WorldDEM-30 © DLR e.V. 2010-2014 and © Airbus
  Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved"
- **Required legal-notice sentence:** "The organisations in charge of the Copernicus programme by law or by
  delegation do not incur any liability for any use of the Copernicus WorldDEM-30"
- **Data citation:** European Space Agency (2022). Copernicus DEM [Data set]. https://doi.org/10.5270/ESA-c5d3d65.
  Copernicus Digital Elevation Model (DEM) was accessed on 26-Sep-2026 from https://registry.opendata.aws/copernicus-dem.
- **Used as:** directly, the land value where GEDTM30 has none (water surfaces; `data/build_v2.py`), and indirectly
  as the main input of GEDTM30 (below). Its obligations bind sub-licensees (Art. 6(e)), so the notice and the
  legal-notice sentence must also reach anyone the game is redistributed to.

### GEDTM30: Global Ensemble Digital Terrain Model 30 m

- **Licence:** CC BY 4.0. https://creativecommons.org/licenses/by/4.0/
- **Dataset:** Ho, Yufeng; Hengl, Tomislav (2025). Global Ensemble Digital Terrain Model 30m (GEDTM30), v1.2.
  Zenodo. https://doi.org/10.5281/zenodo.14900180 (version used: v1.2, https://doi.org/10.5281/zenodo.18887460).
- **Technical paper:** Ho, Y.-F., Grohmann, C. H., Lindsay, J., Reuter, H. I., Parente, L., Witjes, M., Hengl, T.
  (2025). GEDTM30: global ensemble digital terrain model at 30 m and derived multiscale terrain variables. PeerJ 13,
  e19673. https://doi.org/10.7717/peerj.19673
- **Changes:** resampled into 1° tiles, merged with bathymetry and used as training data for a neural terrain
  model.
- **Upstream** (GEDTM30 fuses these):
  - Copernicus DEM GLO-30. Its notice appears above.
  - ALOS World 3D-30m (AW3D30), © JAXA.
  - ICESat-2 ATL08 and GEDI L2 (NASA).
  - GHS building height (European Commission JRC), GLAD and ETH canopy height, and ETOPO 2022 (NOAA).

### GEBCO 2024 Grid

- **Terms:** public domain, under the GEBCO terms of use (acknowledgement required; no implied endorsement).
  https://www.gebco.net/data-products/gridded-bathymetry/terms-of-use
- **Citation:** GEBCO Compilation Group (2024) GEBCO 2024 Grid (doi:10.5285/1c44ce99-0a0d-5f4f-e063-7086abc0ea0f)

### NOAA NCEI CUDEM 1/9 arc-second (Hawaii, Puerto Rico)

- **Terms:** US Government work, not subject to copyright protection within the United States. Listed as CC0 1.0
  on data.gov. Not for navigation.
- **Citation:** Cooperative Institute for Research in Environmental Sciences (CIRES) at the University of Colorado,
  Boulder. 2014: Continuously Updated Digital Elevation Model (CUDEM) - 1/9 Arc-Second Resolution
  Bathymetric-Topographic Tiles. Hawaii and Puerto Rico tiles (2021v1). NOAA National Centers for Environmental
  Information. https://doi.org/10.25921/ds9v-ky35. Accessed 18-Nov-2026.

### USGS 3DEP 1/3 arc-second DEM (SR-15 candidate only; not in exp2-r1)

- **Terms:** public domain. Acknowledgement requested.
  https://www.usgs.gov/information-policies-and-instructions/copyrights-and-credits
- **Acknowledgement:** Map services and data available from U.S. Geological Survey, National Geospatial Program.
- **Citation:** U.S. Geological Survey (2025). 1/3rd arc-second Digital Elevation Models (DEMs) - USGS National Map
  3DEP Downloadable Data Collection. U.S. Geological Survey. Accessed 18-Nov-2026.

### National lidar DTMs (SR-15 v2 + surface-detail node candidates only; not in exp2-r1)

Used by `sr15_v2` (15 m truth) and `surface_r0` (sub-block surface labels), agent L 2026-11-19. Each licence was
checked on the provider's own page / metadata on 2026-11-19; per-file URLs are in
`terrain-slm-data/raw/<source>/PROVENANCE.jsonl`. Required credits once either checkpoint ships:
- **USGS 3DEP 1 m DEM (lidar)** - public domain; credit as the 1/3 arc-second entry above.
- **Kartverket (Norway)**, Nasjonal hoydemodell DTM - CC BY 4.0. Credit: "(c) Kartverket" (CC BY 4.0,
  https://www.kartverket.no/en/api-and-data/terms-of-use).
- **swisstopo swissALTI3D** - swisstopo free geodata (OGD), commercial use allowed, source mandatory. Credit:
  "(c) swisstopo" (https://www.swisstopo.admin.ch/en/terms-of-use-free-geodata-and-geoservices).
- **AHN (Actueel Hoogtebestand Nederland)** - CC0 1.0. Credit (courtesy, not required): "AHN".
- **Toitu Te Whenua Land Information New Zealand**, New Zealand LiDAR 1m DEM - CC BY 4.0. Credit: "Sourced from
  Toitu Te Whenua Land Information New Zealand, licensed for reuse under CC BY 4.0."
- **IGN (France)**, RGE ALTI and LiDAR HD MNT - Licence Ouverte / Open Licence v2.0 (Etalab). Credit:
  "IGN - RGE ALTI, LiDAR HD (Licence Ouverte 2.0)".
- The surface node's climate inputs at the lidar windows come from CHELSA v2.1 (below).

### ETH Global Canopy Height 2020 + ESA WorldCover 2021 (vegetation node candidate only; not in exp2-r1)

Used by `veg_r*` (the vegetation node, `veg.pt`; agent V 2026-11-19) as training targets, binned to the data-v2 60 m
grid by `terrain_slm/data/vegetation.py` (raw tiles + sha256: `terrain-slm-data/raw/vegetation_manifest.json`).
Both licences were checked on the providers' pages on 2026-11-19. Required credits once `veg.pt` ships:
- **ETH Global Canopy Height 2020** - CC BY 4.0 ("provided free of charge, without restriction of use"; cite the
  datasets and the article), https://langnico.github.io/globalcanopyheight/ . Citation: Lang, N., Jetz, W.,
  Schindler, K., & Wegner, J. D. (2023). A high-resolution canopy height model of the Earth. Nature Ecology &
  Evolution, 1-12. Data: doi:10.3929/ethz-b-000609802.
- **ESA WorldCover 10 m 2021 v200** - CC BY 4.0, https://esa-worldcover.org/en/data-access . Credit:
  "(c) ESA WorldCover project 2021 / Contains modified Copernicus Sentinel data (2021) processed by ESA WorldCover
  consortium". Citation: Zanaga, D., Van De Kerchove, R., Daems, D., De Keersmaecker, W., Brockmann, C., Kirches, G.,
  Wevers, J., Cartus, O., Santoro, M., Fritz, S., Lesiv, M., Herold, M., Tsendbazar, N.E., Xu, P., Ramoino, F.,
  Arino, O., 2022. ESA WorldCover 10 m 2021 v200. https://doi.org/10.5281/zenodo.7254221
- Not used: Meta/WRI 1 m canopy height (not needed at 60 m), NASA GEDI footprints (the ETH product is GEDI-trained).

### ERA5 (Copernicus Climate Change Service), via WeatherBench 2

- **Licence:** CC BY 4.0 (Copernicus Climate Data Store, since 2 July 2025). https://creativecommons.org/licenses/by/4.0/
  The WeatherBench 2 copy is distributed under the earlier *Licence to Use Copernicus Products*, which also permits
  this use with attribution.
- **Attribution:** "Generated using or contains modified Copernicus Climate Change Service information 2026.
  Neither the European Commission nor ECMWF is responsible for any use that may be made of the Copernicus
  information or data it contains."
- **CDS catalogue entries:**
  - Copernicus Climate Change Service (2023): ERA5 hourly data on single levels from 1940 to present. Copernicus
    Climate Change Service (C3S) Climate Data Store (CDS). DOI: 10.24381/cds.adbb2d47 (Accessed on 18-NOV-2026)
  - Copernicus Climate Change Service (2023): ERA5 monthly averaged data on single levels from 1940 to present.
    Copernicus Climate Change Service (C3S) Climate Data Store (CDS). DOI: 10.24381/cds.f17050d7 (Accessed on
    18-NOV-2026)
- **Data:**
  - Hersbach, H., Bell, B., Berrisford, P., Biavati, G., Horányi, A., Muñoz Sabater, J., Nicolas, J., Peubey, C.,
    Radu, R., Rozum, I., Schepers, D., Simmons, A., Soci, C., Dee, D., Thépaut, J-N. (2023): ERA5 hourly data on
    single levels from 1940 to present. Copernicus Climate Change Service (C3S) Climate Data Store (CDS), DOI:
    10.24381/cds.adbb2d47 (Accessed on 18-NOV-2026)
  - Hersbach, H., Bell, B., Berrisford, P., Biavati, G., Horányi, A., Muñoz Sabater, J., Nicolas, J., Peubey, C.,
    Radu, R., Rozum, I., Schepers, D., Simmons, A., Soci, C., Dee, D., Thépaut, J-N. (2023): ERA5 monthly averaged
    data on single levels from 1940 to present. Copernicus Climate Change Service (C3S) Climate Data Store (CDS),
    DOI: 10.24381/cds.f17050d7 (Accessed on 18-NOV-2026)
- **Reanalysis paper:** Hersbach, H. et al. (2020). The ERA5 global reanalysis. Quarterly Journal of the Royal
  Meteorological Society 146(730), 1999-2049. https://doi.org/10.1002/qj.3803
- **Mirror:** Rasp, S., Hoyer, S., Merose, A., Langmore, I., Battaglia, P., Russell, T., Sanchez-Gonzalez, A.,
  Yang, V., Carver, R., Agrawal, S., Chantry, M., Ben Bouallegue, Z., Dueben, P., Bromberg, C., Sisk, J.,
  Barrington, L., Bell, A., Sha, F. (2024). WeatherBench 2: A benchmark for the next generation of data-driven
  global weather models. Journal of Advances in Modeling Earth Systems 16(6), e2023MS004019.
  https://doi.org/10.1029/2023MS004019

### CHELSA v2.1 climatologies 1981-2010 (incl. CHELSA-BIOCLIM+ layers)

- **Licence:** CC0 1.0 (no attribution required; citation given as requested by the authors).
- **Data:** Karger, D. N., Conrad, O., Böhner, J., Kawohl, T., Kreft, H., Soria-Auza, R. W., Zimmermann, N. E.,
  Linder, H. P., Kessler, M. (2021). Climatologies at high resolution for the earth's land surface areas. EnviDat.
  https://doi.org/10.16904/envidat.228
- **Model:** Karger, D. N., Conrad, O., Böhner, J., Kawohl, T., Kreft, H., Soria-Auza, R. W., Zimmermann, N. E.,
  Linder, H. P., Kessler, M. (2017). Climatologies at high resolution for the earth's land surface areas.
  Scientific Data 4, 170122. https://doi.org/10.1038/sdata.2017.122
- **BIOCLIM+ (PET, snow water equivalent, aridity index):**
  - Brun, P., Zimmermann, N. E., Hari, C., Pellissier, L., Karger, D. N. (2022). CHELSA-BIOCLIM+ A novel set of
    global climate-related predictors at kilometre-resolution. EnviDat. https://doi.org/10.16904/envidat.332
  - Brun, P., Zimmermann, N. E., Hari, C., Pellissier, L., Karger, D. N. (2022). Global climate-related predictors
    at kilometer resolution for the past and future. Earth System Science Data 14, 5573-5603.
    https://doi.org/10.5194/essd-14-5573-2022

---

### Poly Haven 3D rock scans (RockVAE -> boulder shape bank `rock_bank_v1.sbrb`; agent N, 2026-11-19)

- **Licence:** CC0 1.0 (https://polyhaven.com/license: "All assets (HDRIs, textures and 3D models)" are "licensed as
  CC0"; "You do not need to give credit"). Checked 2026-11-19. No attribution required; a courtesy credit is above.
- **Data:** the 37 models of the Poly Haven category "rocks" (geometry only), 30 of them used (individual rocks /
  boulders + rocks cut out of coast and field scans); per-asset authors, URLs and md5 in
  `/mnt/extrastorage/terrain-slm-data/raw/rocks/sources.json` and the bank manifest
  `stonebreak-game/src/main/resources/worldgen/rocks/rock_bank_v1.json`.
- **Changes:** voxelised, normalised and augmented; a 3D VAE + latent flow trained on them generates new shapes
  (nearest-neighbour IoU to every scan < 0.8), baked to block-sized voxel shapes. No scan is shipped.

---

## Not used by the shipped model (kept for reference)

### CHELSA-TraCE21k v1.0 (paleo experiments only: climate_r2..r4; not in exp2-r1)

Not in the credits: no shipped checkpoint was trained on it. Credit it (with the licence WSL confirms) only if a
paleo-trained climate node ever ships; until WSL confirms the licence in writing it must not.


- **Licence:** [LICENCE AS CONFIRMED BY WSL: CC0 1.0 / CC BY 2.0 / CC BY-SA 4.0; see the audit]
- **Data:** Karger, D. N., Nobis, M. P., Normand, S., Graham, C. H., Zimmermann, N. E. (2020). CHELSA-TraCE21k:
  Downscaled transient temperature and precipitation data since the last glacial maximum. EnviDat.
  https://doi.org/10.16904/envidat.211
- **Model:** Karger, D. N., Nobis, M. P., Normand, S., Graham, C. H., and Zimmermann, N. E. (2023). CHELSA-TraCE21k
  – high-resolution (1 km) downscaled transient temperature and precipitation data since the Last Glacial Maximum.
  Climate of the Past 19, 439-456. https://doi.org/10.5194/cp-19-439-2023
- **Changes:** used only as anomalies against present day, block-averaged to 0.25°, to vary the climate the model
  was trained on.
- **Upstream:** Otto-Bliesner, B., Rosenbloom, N. (2021). Simulation of the Transient Climate of the Last 21,000
  Years (TraCE-21ka). NSF National Center for Atmospheric Research. https://doi.org/10.5065/CXB5-TV56 (CC BY 4.0).
  TraCE-21ka was made possible by the DOE INCITE computing program, and supported by NCAR, the NSF P2C2 program,
  and the DOE Abrupt Change and EaSM programs.
