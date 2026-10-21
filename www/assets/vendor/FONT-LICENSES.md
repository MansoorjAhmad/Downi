# Bundled fonts

Both faces are bundled with the app (no network, no runtime download) and both ship
their licence text next to the file, as their licences require.

## `DOWNISans-Variable.woff2` — the interface face

| | |
|---|---|
| Typeface | **Hubot Sans** |
| Designers | Tobias Bjerrome Ahlin, GitHub, Degarism Studio, Sebastian Carewe |
| Upstream | https://github.com/github/hubot-sans (also distributed by Google Fonts) |
| Licence | **SIL Open Font License 1.1** — see `OFL-HubotSans.txt` |
| Copyright | Copyright 2021 The Hubot Sans Project Authors, with Reserved Font Name "Hubot" |

**This file is a modified version.** It is subset to the characters the app can show and
converted to WOFF2. The OFL permits a derivative to be bundled, embedded and redistributed
with software *"provided that any reserved names are not used by derivative works"*, and it
counts a change of format as a modification — so the family is renamed **"DOWNI Sans"** and
the reserved name "Hubot" appears nowhere in the font. The original copyright notice, the
licence text and the upstream attribution are all retained, as the OFL requires.

Axes kept: `wght` 200–900, `wdth` 75–125. The width axis is what gives the uppercase
labels their technical squareness. The upstream file's default instance is Condensed
ExtraLight, so the app pins the width to normal (`font-stretch: normal` on `body`).

## `JetBrainsMono.woff2` — the data layer

| | |
|---|---|
| Typeface | **JetBrains Mono** |
| Designers | JetBrains |
| Upstream | https://github.com/JetBrains/JetBrainsMono |
| Licence | **SIL Open Font License 1.1** — see `OFL-JetBrainsMono.txt` |

Unmodified, as distributed. It carries the compact technical values: percentages, speeds,
ETAs, file sizes, versions and links.
