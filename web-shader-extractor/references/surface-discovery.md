# Surface Discovery

Surface discovery has two jobs: inventory candidates, then prove causality between the requested visual and one or more rendering surfaces. Inventory alone never decides the target.

## Surfaces To Enumerate

- visible and hidden `canvas`
- canvases inside same-origin iframes; for cross-origin iframes, record frame bounds, screenshots, network/frame metadata, or tool-accessible observations without bypassing origin restrictions
- `OffscreenCanvas` transferred to workers
- WebGPU canvas contexts
- DOM masks, clip paths, sticky containers, SVG filters, videos, and images composited with canvas
- shared renderer canvases that persist across routes

## Candidate Record

Record each candidate as:

```json
{
  "id": "surface-1",
  "selector": "canvas#hero",
  "frame": "main",
  "bounds": { "x": 0, "y": 0, "width": 1440, "height": 900 },
  "cssSize": { "width": 1440, "height": 900 },
  "backingSize": { "width": 2880, "height": 1800 },
  "dpr": 2,
  "visibility": "visible",
  "zIndex": "auto",
  "context": "webgl2|webgl|webgpu|2d|bitmaprenderer|unknown",
  "owner": "main-thread|iframe|worker|unknown",
  "routePersistence": "single-route|persistent|unknown",
  "notes": []
}
```

## Context Probes

Use non-destructive runtime evaluation first:

- inspect size, bounding rect, computed style, opacity, transform, pointer-events, z-index
- check known dataset hints such as `data-engine`, `data-renderer`, embed IDs
- detect existing contexts without creating unrelated new contexts when possible
- observe worker script URLs, `transferControlToOffscreen`, and WebGPU adapter/device calls if available

If context creation must be probed, label that as probe-induced evidence so it is not confused with the page's real context.

## Target Model

The target may be a surface group:

```text
canvas#background
+ canvas#particles
+ DOM mask
+ scroll progress
```

Use `targetSet` in Scout Card and Manifest. Do not force the result into a single `targetCanvas`.

## Attribution As Hypothesis Elimination

Treat each candidate (or candidate group) as a hypothesis in the ledger: "this surface set produces the requested visual." Attribution is done when probes have eliminated every alternative, not when one candidate merely looks plausible.

Apply the discriminating probe rule from `references/recon-kernel.md`: an observation shared by many candidates (largest canvas, animates continuously, framework-looking dataset attribute, bundle contains shader strings) discriminates nothing and is weak by definition. An observation only one hypothesis predicts is strong — for example, the target crop loses the distortion only when `surface-2` is hidden, or scroll changes uniforms on `surface-1` and the visual phase follows.

Record per-candidate observation dimensions to decide which probe to run next:

- `visualCoverage`: overlap with the target visual region
- `temporalActivity`: frame-to-frame change on the target crop
- `interactionCoupling`: pointer, scroll, resize, or route changes affect the target
- `sectionCoupling`: page sections, sticky positioning, masks, and clips
- `routePersistence`: whether the surface persists across route changes
- `ablationImpact`: what target pixels/behaviors disappear when hidden or frozen
- `ownershipEvidence`: how well context and renderer owner can be traced

These dimensions are heuristics for probe ordering. Only `ablationImpact`-class evidence (the effect disappears with exactly this surface set) settles attribution; the target may also be a group — a small overlay, mask, or DOM layer can be essential even when coverage is tiny.

## Attribution Actions

Use the cheapest probe that splits the remaining candidates:

1. style, bounds, and visibility observation
2. multi-frame diff on a target crop
3. temporary `visibility:hidden`, opacity, transform, or clip ablation
4. freeze `requestAnimationFrame` or replace a candidate surface output
5. pointer sweep
6. small scroll
7. resize
8. route switch
9. owner stack or preload probe

All modifications are brief, reversible, and observational. Do not persist source-site changes.
