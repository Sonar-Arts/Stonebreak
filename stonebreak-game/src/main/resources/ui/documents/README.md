# Shipped UI document screens

One exported screen per file: `<id>.sbui` (for example `pause.sbui`), written by the Open Mason
UI editor's "Deploy to game" step.

At runtime `DocumentScreenHost.open("<id>", ...)` loads `ui/documents/<id>.sbui` from the classpath
through `GameUiDocuments.openBound`, which refuses the screen (and keeps the legacy one) when its
activation, asset or input gate fails. A screen with no file here is legacy. Roll a shipped screen
back with `-Dstonebreak.ui.legacy=<id,...>` (or `=all`); open one in-game for checking with
`-Dstonebreak.uiscreen=<id>`.
