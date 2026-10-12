# Shared UI resources

The packaged root for SHARED dependency rows of shipped UI documents (`GameUiAssets.RESOURCE_ROOT`).
Layout: `<namespace>/<path><ext>`, so `stonebreak:ui/textures/panel` (an SBT texture) lives at
`stonebreak/ui/textures/panel.sbt`. The Open Mason UI editor's "Deploy to game" step writes the rows
an export lists as must-ship; an SBUI whose required rows are missing here is refused at open.
