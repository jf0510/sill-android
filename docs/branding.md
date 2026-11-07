# Icon and logo

The launcher icon and README logo adapt the original Sill icon by Noah Saffer
from `iOSClient/Assets.xcassets/AppIcon.appiconset/AppIcon.png` at upstream commit
`41e8cb8801f8e9d387e27a0d498dee193d17e9ec` (Apache-2.0).
The inner tablet and document are replaced with a grayscale Android robot's
head and upper body, with no inner device frame. The outer monitor composition,
stand, and black background follow the original Sill artwork.

The Android robot is reproduced or modified from work created and shared by
Google and used according to terms described in the Creative Commons 3.0
Attribution License: https://creativecommons.org/licenses/by/3.0/

The [official flat robot head](https://developer.android.com/static/images/brand/android-head_flat.png)
was used as a geometry reference. See the [Android robot guidelines](https://developer.android.com/distribute/marketing-tools/brand-guidelines#android-robot)
and [NOTICE](../NOTICE). The robot component retains its CC BY 3.0 attribution;
the code's Apache-2.0 license does not replace that component's license.
No endorsement or trademark rights are claimed.

## Asset production

The raster artwork was produced with the built-in image generation tool using
the upstream icon as the edit target and the official robot head as a reference.
The selected output is preserved in `docs/images/logo.png` and copied to
`app/src/main/res/drawable-nodpi/sill_icon.png` without additional image processing.

Generation prompt:

```text
PRECISE LOCAL EDIT. Image 1 is the ORIGINAL Sill app icon and MUST be the base image. Image 2 is ONLY a reference for the Android robot's official head shape. Preserve the ORIGINAL Sill icon's outer monitor frame, bezel, screen boundary, stand/base, placement, dimensions, corner radii, metallic gray gradients, highlights, shadows, black background, and outer margins exactly as in Image 1. Do not redesign, redraw, resize, thicken, recolor or relight any part of that original exterior. Change ONLY the CONTENT INSIDE the monitor's existing dark display area. Remove the entire small tilted tablet and document that currently appear inside that display. In their place, directly inside the same screen area, place a precisely shaped light-gray/white Android robot upper-body bust, subtly tilted counterclockwise, with full head and antennae, correct dark oval eyes copied in proportion from Image 2, a simple rounded upper torso and capsule arms. No legs. Grayscale, no green or blue. Robot is fully confined to the original dark screen and may be clipped along its lower screen edge; never cover, alter, or extend onto the original bezel. No second frame, no inner tablet, no inner box, no text. This must look like the SAME original Sill icon with different content playing on its screen, NOT a new monitor icon. Match the original square canvas and exact exterior composition; only the original screen content may change.
```

## Android launcher presentation

The launcher uses an adaptive icon with a black background and the unchanged
raster artwork inset by 16.67% in its foreground layer. This fills the launcher
mask without the white backing applied to legacy square icons. Both `icon` and
`roundIcon` reference the adaptive resource. The README retains the square artwork.
