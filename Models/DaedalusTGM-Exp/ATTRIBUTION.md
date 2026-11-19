# DaedalusTGM-Exp-2: data attribution

The DaedalusTGM-Exp-2 terrain model that ships with Stonebreak was trained on the datasets below. The game contains
only the trained model weights. None of these datasets is redistributed with it. Each licensor's required notice
is given verbatim. The citations follow each provider's own "how to cite" text.

Licence audit and reasoning: `Dev Working/docs/DaedalusTGM-Exp-2-Data-Licences.md` (local, 2026-11-18).

> **Release gate:** the CHELSA-TraCE21k entry may stay only after its licence has been confirmed in writing (see
> the audit). If the paleo data is dropped and the climate node retrained, delete that entry and its upstream
> line. No WorldClim-derived weights may ship.

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
- **USGS 3DEP**: Map services and data available from U.S. Geological Survey, National Geospatial Program.
- **ERA5**: Generated using or contains modified Copernicus Climate Change Service information 2026, licensed under
  CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/). ERA5 accessed via WeatherBench 2 (Google Research).
- **CHELSA v2.1**: Climatologies at high resolution for the earth's land surface areas (Karger et al.), and
  CHELSA-BIOCLIM+ (Brun et al.), WSL.
- **CHELSA-TraCE21k**: downscaled transient climate since the Last Glacial Maximum (Karger et al.), WSL, licensed
  under [LICENCE AS CONFIRMED BY WSL], based on the NCAR TraCE-21ka simulation.

Stonebreak and DaedalusTGM-Exp-2 are not endorsed by Airbus, DLR, ESA, the European Union, ECMWF, the European
Commission, GEBCO, the IHO, the IOC, NOAA, the USGS, WSL, OpenGeoHub or any other data provider.

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

### USGS 3DEP 1/3 arc-second DEM

- **Terms:** public domain. Acknowledgement requested.
  https://www.usgs.gov/information-policies-and-instructions/copyrights-and-credits
- **Acknowledgement:** Map services and data available from U.S. Geological Survey, National Geospatial Program.
- **Citation:** U.S. Geological Survey (2025). 1/3rd arc-second Digital Elevation Models (DEMs) - USGS National Map
  3DEP Downloadable Data Collection. U.S. Geological Survey. Accessed 18-Nov-2026.

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

### CHELSA-TraCE21k v1.0 (keep only after licence confirmation)

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
