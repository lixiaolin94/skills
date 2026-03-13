---
name: figure
description: >
  Generate professional SVG illustrations for technical documents and academic papers.
  Use this skill whenever the user asks for diagrams, charts, figures, illustrations,
  or visual explanations to accompany written content — especially comparison diagrams,
  signal flow charts, architecture overviews, timeline diagrams, or conceptual models.
  Also trigger when the user says things like "add a chart here", "illustrate this section",
  "draw a diagram for...", "配图", "画个图", "示意图", "对比图", or references inserting
  visuals into markdown, technical reports, or papers. Even if the user just says
  "can you explain this visually" or "this section needs a figure", use this skill.
---

# Technical Illustration Generation

This skill produces SVG illustrations in a consistent **colorful technical textbook** style — the kind you see in high-quality computer science, electronics, and physics educational materials. The style combines clean geometric precision with a warm, approachable pastel color palette and pixel-font aesthetics.

## Core Philosophy

A good technical illustration makes the invisible visible. It shows the reader how electrons flow through a CRT, how cache hierarchies are organized, how color spaces map to human perception. The illustration should feel like a beautifully crafted page from a premium technical reference book — educational, precise, yet visually inviting.

Before drawing anything, ask: **what physical or conceptual structure does the reader need to see?** The figure should reveal structure that text alone cannot convey.

## Visual Style

The style is **technical textbook illustration** — think Tanenbaum's computer architecture books or Apple's developer documentation, not IEEE paper figures or marketing infographics.

### Color Palette

Use a **soft pastel palette** with semantic meaning. Colors distinguish categories, layers, or functional roles — not decoration. Each color should map to a concept.

| Color | Hex (fill / stroke) | Typical Usage |
|-------|---------------------|---------------|
| Pink/salmon | `#f4a4a8` / `#d88088` | Proprietary systems, highlight elements, active signals, top-tier (registers) |
| Lavender/purple | `#c0b0e8` / `#9888c0` | Memory (DRAM), intermediate layers, mid-tier hierarchy |
| Lavender-pink | `#d8b0d8` / `#b890b8` | Cache layers, blended-tier elements between pink and purple |
| Mint/green | `#88ddb0` / `#60b888` | Processor cores, open-source systems, positive states |
| Sky blue | `#a0d4f0` / `#70a8c8` | Storage, large capacity, base layers, open-source APIs |
| Cornflower blue | `#a0b8e8` / `#7090c0` | Open-source software systems (Vulkan, Linux, etc.) |
| Yellow/gold | `#ffd840` / `#ccb030` | Control units, energy, light sources, signals |
| Pure white | `#fff` | Background, text on dark fills |
| Light gray | `#e8e8e8` / `#ccc` | Container backgrounds, bezels, secondary structures |
| Dark gray / black | `#333` / `#222` | Text, outlines, structural lines |

**Saturated colors** (pure red `#ff0000`, green `#00cc00`, blue `#0000ff`) are reserved for when the subject IS color itself — RGB channels, color spaces, spectral diagrams. For everything else, use the soft pastels above.

### Grayscale Palette (Academic Black & White)

When the user requests **black-and-white**, **grayscale**, **学术风格**, **论文配图**, or the target is print/PDF where color is unavailable, switch to this palette. The key principle: **maximize luminance separation** between adjacent elements — every region must be instantly distinguishable without color.

**6 gray levels**, each separated by ≥ 25% perceived luminance difference:

| Level | Fill | Stroke | Text on fill | Typical Usage |
|-------|------|--------|-------------|---------------|
| G1 (near-black) | `#2a2a2a` | `#111` | `#fff` | Top-tier / most important / smallest elements |
| G2 (dark) | `#555` | `#333` | `#fff` | Secondary emphasis, mid-high tier |
| G3 (mid) | `#888` | `#666` | `#fff` | Middle tier, neutral |
| G4 (light-mid) | `#b0b0b0` | `#888` | `#222` | Lower tier, supporting elements |
| G5 (light) | `#d8d8d8` | `#aaa` | `#222` | Base tier, largest elements, backgrounds |
| G6 (near-white) | `#f0f0f0` | `#ccc` | `#333` | Containers, grouping boxes |

**Text contrast rules** — non-negotiable for readability:
- Fill darker than `#888` → use **white text** (`#fff` or `#eee`)
- Fill lighter than `#888` → use **dark text** (`#222` or `#333`)
- Never place `#555` text on `#888` fill — insufficient contrast

**Grayscale-specific differentiation techniques** (compensating for loss of color):
- **Patterns are subtle background texture**, not foreground detail — pattern lines must be light enough that overlaid text remains effortlessly readable
- **Stroke width variation**: primary shapes `1.2–1.5px`, secondary `0.8–1.0px` (slightly heavier than colorful mode for print clarity)
- **Fill + pattern combos** — pattern strokes use very low contrast against their background fill (≤ 15% darker), just enough to perceive the texture:

```xml
<defs>
  <!-- Solid fills (no pattern needed when grays are far apart) -->
  <!-- G1 #2a2a2a, G3 #888, G5 #d8d8d8 — sufficient contrast alone -->

  <!-- Subtle patterns — foreground lines barely visible, never competing with text -->
  <pattern id="bw-crosshatch" width="8" height="8" patternUnits="userSpaceOnUse">
    <rect width="8" height="8" fill="#d8d8d8"/>
    <path d="M0,0 L8,8 M8,0 L0,8" stroke="#c4c4c4" stroke-width="0.3"/>
  </pattern>
  <pattern id="bw-diagonal" width="8" height="8" patternUnits="userSpaceOnUse">
    <rect width="8" height="8" fill="#e8e8e8"/>
    <path d="M0,8 L8,0" stroke="#d4d4d4" stroke-width="0.3"/>
  </pattern>
  <pattern id="bw-dots" width="6" height="6" patternUnits="userSpaceOnUse">
    <rect width="6" height="6" fill="#e0e0e0"/>
    <circle cx="3" cy="3" r="0.5" fill="#ccc"/>
  </pattern>
  <pattern id="bw-horizontal" width="8" height="5" patternUnits="userSpaceOnUse">
    <rect width="8" height="5" fill="#d0d0d0"/>
    <line x1="0" y1="2.5" x2="8" y2="2.5" stroke="#bfbfbf" stroke-width="0.3"/>
  </pattern>
</defs>
```

**Text over patterns** — critical rule:
- Text always takes visual priority over any pattern beneath it
- If a pattern makes text harder to read, the pattern is too strong — reduce stroke-width or lighten stroke color
- Pattern exists only to give the shape a subtle "different from its neighbor" feel, not to draw attention

**Grayscale selection logic**:
1. If the diagram has ≤ 4 distinct categories → use G1, G3, G5, G6 (maximum spread, **no patterns needed**)
2. If 5–6 categories → use all 6 levels, add subtle patterns to the two closest pairs
3. If > 6 categories → group some with shared gray + distinct subtle pattern (crosshatch vs diagonal vs dots)
4. **Prefer pure gray-level separation first** — only add patterns when two elements would otherwise be indistinguishable

**Stroke color in grayscale** still follows the "darker shade of fill" rule:
```
G5 fill #d8d8d8 → stroke #aaa
G4 fill #b0b0b0 → stroke #888
G3 fill #888    → stroke #666
G2 fill #555    → stroke #333
G1 fill #2a2a2a → stroke #111
```

**Flat fills are the default.** Most shapes use simple solid pastel colors (or grayscale fills in B&W mode). Hatching patterns are used **selectively** in colorful mode for emphasis — mainly for large prominent memory blocks or to indicate special states. In grayscale mode, patterns are used more liberally as a primary differentiation tool:

- **Diagonal hatching** (`/` lines over light gray): specifically marks "legacy" or "deprecated" items
- **Crosshatch** (`X` lines over pastel): optional texture for very large shapes (full-width DRAM bars, L3 cache blocks) to add visual richness
- **Grid** (small squares over pastel): optional for dense arrays (GPU core grids)

When in doubt, use a flat pastel fill. Hatching is the exception, not the rule. Define SVG `<pattern>` elements when needed:

```xml
<defs>
  <!-- Crosshatch pattern over lavender fill -->
  <pattern id="hatch-lavender" width="6" height="6" patternUnits="userSpaceOnUse">
    <rect width="6" height="6" fill="#c8b8e8"/>
    <path d="M0,0 L6,6 M6,0 L0,6" stroke="#b0a0d0" stroke-width="0.4"/>
  </pattern>
  <!-- Grid pattern over pink fill -->
  <pattern id="grid-pink" width="5" height="5" patternUnits="userSpaceOnUse">
    <rect width="5" height="5" fill="#f4a4a8"/>
    <path d="M5,0 L5,5 M0,5 L5,5" stroke="#d89098" stroke-width="0.3"/>
  </pattern>
  <!-- Diagonal hatch for "legacy" or "disabled" items -->
  <pattern id="hatch-diagonal" width="6" height="6" patternUnits="userSpaceOnUse">
    <rect width="6" height="6" fill="#f0f0f0"/>
    <path d="M0,6 L6,0" stroke="#ccc" stroke-width="0.5"/>
  </pattern>
</defs>
```

The diagonal hatch is the most commonly needed pattern (for legacy/disabled states). Others are optional flourishes.

### Typography

Use a **monospace font** with ALL CAPS for all labels. This is the most recognizable stylistic signature.

```xml
font-family="'SF Mono', 'Fira Code', 'Courier New', monospace"
text-transform="uppercase"  /* Or simply write text in CAPS in the SVG */
letter-spacing="0.5px"
```

All text in the illustration must be UPPERCASE. No exceptions.

| Element | Size | Color |
|---------|------|-------|
| Major labels (component names) | 11–14 | `#222` or `#333` |
| Secondary labels (descriptions) | 9–11 | `#555` or `#666` |
| Axis labels, units | 8–10 | `#444` |
| Small annotations (values, specs) | 7–8 | `#666` or `#888` |

Do not use bold or italic. Hierarchy comes from size and placement.

### Sizing System

All dimensions are based on a **960px-wide viewBox**. When viewBox width changes, scale proportionally.

#### Spacing Tokens

| Token | Value | Usage |
|-------|-------|-------|
| `pad-outer` | 50–60px | Canvas edge to any content |
| `pad-inner` | 20–30px | Inside containers (boxes, dashed groups) to their content |
| `gap-large` | 40–60px | Between major sections (e.g., left panel vs right panel) |
| `gap-medium` | 20–30px | Between related elements (e.g., stacked boxes in a hierarchy) |
| `gap-small` | 8–12px | Between label and its subject, between tightly coupled items |
| `leader-offset` | 15–25px | Distance from diagram edge to the start of leader-line labels |

#### Element Sizing

| Element | Width | Height | Notes |
|---------|-------|--------|-------|
| Primary box (component) | 120–200px | 32–44px | Scales with label length |
| Secondary box (sub-component) | 80–140px | 24–32px | |
| Wide bar (spanning full section) | section width | 32–40px | e.g., DRAM, VULKAN |
| Small cell (grid/array) | 18–24px | 14–20px | e.g., GPU cores, color swatches |
| Vertex/anchor dot | r = 3–5px | — | Circle at connection points |
| Arrow marker | 8×6px | — | Standard triangle head |

#### Proportional Rules

- **Side-by-side comparison**: split canvas at `x = viewBox_width / 2`, each half gets `pad-outer` on the outer edge and `gap-large / 2` on the inner edge
- **Hierarchy (top-to-bottom)**: top-most element starts at `y = pad-outer + 20`, each subsequent layer adds `gap-medium` + element height. Wider elements should be proportionally wider (e.g., bottom layer = 80% of canvas width, top layer = 30%)
- **Flow (left-to-right)**: first box at `x = pad-outer`, each subsequent box at previous box's right edge + `gap-medium`. Arrow between boxes = `gap-medium` with marker
- **Label vertical alignment**: label text baseline should be vertically centered on the element it describes (element_center_y + font_size × 0.35)

### Label Placement — Leader Lines

The primary annotation method is **labels placed outside the illustration** connected by thin **leader lines** (引线) pointing to their subjects. This keeps the diagram clean and uncluttered.

Leader line rules:
- Thin stroke: `0.5–0.8px`, color `#333` or `#555`
- Straight lines — vertical drop then horizontal to label, or single angled line
- Small tick mark or dot at the attachment point on the subject
- Labels positioned in the margins (above, below, or sides of the main diagram)
- Consistent alignment: multiple labels on the same side should be left-aligned or right-aligned

```xml
<!-- Leader line example: label above pointing down to subject -->
<text x="400" y="50" font-size="10" fill="#333"
      font-family="'SF Mono', monospace">LCD PANEL</text>
<line x1="400" y1="55" x2="400" y2="120"
      stroke="#333" stroke-width="0.6"/>
```

### Line Work

| Element | Stroke width | Style |
|---------|-------------|-------|
| Primary outlines (shapes, boxes) | 1.0–1.2 | Solid, use stroke color matching fill (e.g., `#60b888` for green, `#d88088` for pink) |
| Structural lines (axes, dividers) | 0.8–1.2 | Solid, `#333` or `#555` |
| Leader lines (annotations) | 0.5–0.8 | Solid, `#333`–`#555` |
| Dashed containers (grouping) | 1.0–1.5 | `stroke-dasharray="8,4"`, `#888` |
| Light/energy flow | 1.0–1.5 | Wavy path, golden/yellow |
| Ghost/disabled elements | 0.5–0.8 | Dashed, `#bbb` |

Arrow markers should be **small, filled triangles** — clean and simple:

```xml
<marker id="arr" markerWidth="8" markerHeight="6"
        refX="8" refY="3" orient="auto">
  <path d="M0,0 L8,3 L0,6 Z" fill="#333"/>
</marker>
<!-- Bidirectional arrow head (both ends) -->
<marker id="arr-back" markerWidth="8" markerHeight="6"
        refX="0" refY="3" orient="auto">
  <path d="M8,0 L0,3 L8,6 Z" fill="#333"/>
</marker>
```

### Layout

- **Explicit white background**: always place a `<rect width="100%" height="100%" fill="#fff"/>` as the first element after `<defs>`. SVG has no default background — without this rect, exports to PNG will be transparent
- **Wide aspect ratio**: viewBox typically `0 0 960 400` to `0 0 960 600` (roughly 2:1)
- For taller content (pyramids, vertical hierarchies): `0 0 960 500` to `0 0 960 700`
- Content centered or balanced within the canvas

#### Layout Algorithms by Diagram Type

**Pyramid / Hierarchy (N layers, top-small → bottom-wide)**:
```
W = 960, H = N * 110 + 120
apex = (W/2, 60)
base_left = (W/2 - W*0.38, H - 40)
base_right = (W/2 + W*0.38, H - 40)
For layer boundary at fraction t (0=apex, 1=base):
  x_left(t)  = apex.x + (base_left.x - apex.x) * t
  x_right(t) = apex.x + (base_right.x - apex.x) * t
Divide t evenly: layer_i uses t = i/N to (i+1)/N
Side labels: x = x_left - 25 (right-aligned) and x = x_right + 15
```

**Side-by-side Comparison (A vs B)**:
```
W = 960, H = 400~500
Left panel:  x = 50  to  W/2 - 30  (content width ≈ 400)
Right panel: x = W/2 + 30  to  W - 50
Title "A" at (left_center, 35), title "B" at (right_center, 35)
Both panels use identical y-coordinates for visual alignment
```

**Flow / Pipeline (N stages, left → right)**:
```
W = 960, H = 300~400
available_width = W - 2 * pad_outer
box_width = (available_width - (N-1) * gap_medium) / N
  capped at max 160px; if smaller than 80px, increase H and wrap to 2 rows
box_x[i] = pad_outer + i * (box_width + gap_medium)
box_y = H/2 - box_height/2  (vertically centered)
Arrow between: from box_x[i] + box_width to box_x[i+1], at box_center_y
```

**Data Chart (x-y axes)**:
```
W = 960, H = 360~450
plot_area: x = 100 to W - 60, y = 50 to H - 70
  → gives ~800px wide, ~280px tall plot area
Origin at (100, H-70), x-axis rightward, y-axis upward
Tick marks: 4px lines perpendicular to axis, labels offset 15px
Grid lines: stroke=#eee, width=0.3
```

**Stacked Rows (e.g., comparison swatches, tables)**:
```
W = 960, H = row_count * row_height + 2 * pad_outer + header_height
label_column: x = pad_outer to 220 (right-aligned text)
data_area: x = 225 to W - pad_outer
row_height = 80~100px per row
cell_width = data_area_width / cell_count
```

```xml
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 960 480"
     font-family="'SF Mono', 'Fira Code', 'Courier New', monospace">
  <defs>
    <!-- patterns, markers -->
  </defs>
  <!-- White background (required — SVG has no default bg) -->
  <rect width="100%" height="100%" fill="#fff"/>
  <!-- content -->
</svg>
```

## Critical Rules

### 1. Text Must Never Overlap Lines or Other Elements

Every text label must have clear space. Use leader lines to place labels in empty margins rather than on top of diagram content. After placing all elements, verify no text bounding box intersects any path, shape, or other text.

### 2. ALL CAPS Monospace for Everything

Every single piece of text — labels, titles, values, units, annotations — must be uppercase monospace. This is the non-negotiable visual signature. Write `CPU REGISTERS` not `CPU Registers`, write `MAIN MEMORY (DRAM)` not `Main Memory (DRAM)`.

### 3. Color Carries Semantic Meaning

Never use color randomly. Establish a color mapping at the start (e.g., pink = proprietary, blue = open source, purple = memory) and apply it consistently throughout the figure. When the figure has a legend, include one:

```xml
<!-- Legend -->
<rect x="300" y="420" width="16" height="12" fill="url(#hatch-diagonal)" stroke="#999"/>
<text x="322" y="430" font-size="9" fill="#333">LEGACY</text>
<rect x="420" y="420" width="16" height="12" fill="#f4a4a8" stroke="#d08080"/>
<text x="442" y="430" font-size="9" fill="#333">PROPRIETARY</text>
<rect x="570" y="420" width="16" height="12" fill="#a0c8f0" stroke="#6098c0"/>
<text x="592" y="430" font-size="9" fill="#333">OPEN SOURCE</text>
```

### 4. Size Encodes Magnitude

When representing hierarchical quantities (cache sizes, bandwidths, capacities), use **physical size** to encode relative magnitude. A DRAM block should be visibly larger than L3 Cache, which should be larger than L2, etc. The reader should feel the scale difference without reading labels.

### 5. Hatching Is Selective, Not Default

Default to flat pastel fills. Use hatching only for:
- **Diagonal hatch**: legacy/deprecated/disabled items (e.g., OpenGL marked as legacy)
- **Crosshatch**: optionally on very large blocks to add texture (e.g., a full-width DRAM bar)
- **Grid**: optionally on dense repeated elements (e.g., GPU core arrays)

If the figure doesn't have legacy/disabled elements, it may have no hatching at all — and that's correct.

### 6. Curves Are Allowed Where Physically Meaningful

Unlike strict block diagrams, this style freely uses:
- **Curved shapes** for physical objects (CRT tubes, lenses, magnets, coils)
- **Wavy lines** for light, energy, electromagnetic waves
- **Ellipses** for 3D perspective views (rotation orbits, tube necks)
- **Bezier curves** for data trajectories, spectral curves, response functions

Orthogonal (straight horizontal/vertical) connections are still preferred for **abstract logical connections** (box-to-box data flow, hierarchy arrows). But when illustrating physical reality, draw what the thing actually looks like.

### 7. Zoom/Detail Insets

For complex structures, use a **magnification circle** to show detail:
- Draw a circle with a thin stroke around the zoomed area
- Connect it to an enlarged version with expansion lines
- The enlarged view shows internal detail (e.g., phosphor dots, pixel structure)

```xml
<!-- Magnification inset -->
<circle cx="200" cy="250" r="60" fill="none" stroke="#999" stroke-width="0.8"/>
<line x1="145" y1="210" x2="50" y2="100" stroke="#999" stroke-width="0.5"/>
<line x1="255" y1="210" x2="350" y2="100" stroke="#999" stroke-width="0.5"/>
<!-- Enlarged detail view -->
<circle cx="200" cy="60" r="80" fill="#fff" stroke="#999" stroke-width="0.8"/>
<!-- detail content inside -->
```

### 8. Stroke Color Matches Fill Color

Shape outlines should NOT all be `#333` black. Instead, each shape's stroke should be **a darker shade of its own fill color**. This is critical for the soft, cohesive look:

```
Green fill  #88ddb0  →  stroke #60b888
Yellow fill #ffd840  →  stroke #ccb030
Pink fill   #f4a4a8  →  stroke #d88088
Blue fill   #a0d4f0  →  stroke #70a8c8
Purple fill #c0b0e8  →  stroke #9888c0
```

Only use `#333` strokes for structural lines (axes, arrows, leader lines) — not for shape outlines.

### 9. No Decorative Noise

Despite using color and patterns, the style remains clean:
- No drop shadows
- No gradients on structural elements (gradients only for representing actual light/color)
- No rounded-corner panels or card-style containers
- No icons or emoji
- No background texture on the canvas itself

## Figure Types & Patterns

### Block/Architecture Diagram
For CPU/GPU structures, memory hierarchies, API layers. Use colored rectangles (flat pastel fills by default), size encoding for relative scale, straight arrows for data flow. **Stroke colors should be a darker shade of the fill** (not `#333`) — e.g., green fill `#88ddb0` gets stroke `#60b888`, pink fill `#f4a4a8` gets stroke `#d88088`. This creates a softer, more cohesive look than black outlines. Group related blocks with dashed containers labeled at top-left.

### Cross-Section / Cutaway Diagram
For physical devices (displays, sensors, circuits). Draw the actual physical layers as stacked shapes. Use leader lines from margins to label each layer. Light/energy flow shown with wavy golden lines or dotted particle paths. Materials shown with distinct colors/patterns.

### Scientific Data Chart
For spectral responses, gamma curves, color spaces. Standard x-y axes with thin lines, labeled ticks. Data curves use semantic color (blue for S cone, green for M cone, red for L cone). Background may show spectrum gradient when relevant. Grid lines very light (`#eee`).

### Flow / Pipeline Diagram
For shader pipelines, data processing chains. Stages shown as squares or boxes in a horizontal row, connected by arrows. Each stage may contain a small iconic representation of what it does. Flow moves left-to-right.

### Hierarchy / Pyramid Diagram
For memory hierarchy, protocol stacks. Triangular/trapezoidal layers stacked vertically, wider at bottom. Each layer a different pastel color. Size labels on left side, latency labels on right side. Arrows along edges indicating direction of increase.

### Mathematical / 3D Wireframe
For vertices, meshes, rotation matrices. Use thin blue lines for wireframe edges, small open circles at vertices. Equations shown in monospace alongside the visualization. 3D perspective with simple parallel projection.

### Color / Pixel Demonstration
For RGB formats, blending modes, color spaces. Use actual colors as fills. Small color swatches next to numeric values. Show the mathematical operation (channel values, operators) alongside visual result.

## Figure Design Process

### Step 1: Identify What Needs to Be Visualized
Read the surrounding text. Is it:
- A physical structure that needs a cross-section? → Cutaway diagram
- An abstract hierarchy? → Pyramid or block diagram
- A data relationship? → Chart with curves
- A process/pipeline? → Flow diagram
- A mathematical concept? → Wireframe + equations

### Step 2: Establish the Color Map
Decide which semantic categories exist and assign pastel colors. Write this down before drawing. Examples:
- Memory types: pink (registers) → purple (cache) → blue (DRAM) → sky blue (storage)
- System types: hatched (legacy), pink (proprietary), blue (open source)
- Signal types: yellow (light), blue (electrons), red (current)

### Step 3: Layout with Size Encoding
Sketch the spatial arrangement. Larger = more capacity/importance. Consider:
- Left-to-right for flow/time
- Top-to-bottom for hierarchy (fast/small at top, slow/big at bottom)
- Side-by-side for comparisons (CPU left, GPU right)

### Step 4: Add Labels with Leader Lines
Place ALL CAPS labels in the margins. Connect to subjects with thin leader lines. Align labels neatly. Ensure no overlaps.

### Step 5: Polish and Verify
Use darker-shade strokes matching each fill color (not uniform `#333` outlines). Add hatching only where semantically needed (legacy items). Add small detail elements (dots for data points, wavy lines for energy). Verify no text overlaps any other element.

## SVG Template

```xml
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 960 480"
     font-family="'SF Mono', 'Fira Code', 'Courier New', monospace">

  <defs>
    <!-- Hatching patterns -->
    <pattern id="hatch-purple" width="6" height="6" patternUnits="userSpaceOnUse">
      <rect width="6" height="6" fill="#c8b8e8"/>
      <path d="M0,0 L6,6 M6,0 L0,6" stroke="#b0a0d0" stroke-width="0.4"/>
    </pattern>
    <pattern id="hatch-pink" width="5" height="5" patternUnits="userSpaceOnUse">
      <rect width="5" height="5" fill="#f4a4a8"/>
      <path d="M5,0 L5,5 M0,5 L5,5" stroke="#d89098" stroke-width="0.3"/>
    </pattern>
    <pattern id="hatch-blue" width="6" height="6" patternUnits="userSpaceOnUse">
      <rect width="6" height="6" fill="#a0d0f0"/>
      <path d="M0,0 L6,6 M6,0 L0,6" stroke="#80b0d0" stroke-width="0.4"/>
    </pattern>
    <pattern id="hatch-green" width="5" height="5" patternUnits="userSpaceOnUse">
      <rect width="5" height="5" fill="#a8e0c0"/>
      <path d="M5,0 L5,5 M0,5 L5,5" stroke="#80c0a0" stroke-width="0.3"/>
    </pattern>
    <pattern id="hatch-diagonal" width="6" height="6" patternUnits="userSpaceOnUse">
      <rect width="6" height="6" fill="#f0f0f0"/>
      <path d="M0,6 L6,0" stroke="#ccc" stroke-width="0.5"/>
    </pattern>

    <!-- Arrow markers -->
    <marker id="arr" markerWidth="8" markerHeight="6" refX="8" refY="3" orient="auto">
      <path d="M0,0 L8,3 L0,6 Z" fill="#333"/>
    </marker>
    <marker id="arr-back" markerWidth="8" markerHeight="6" refX="0" refY="3" orient="auto">
      <path d="M8,0 L0,3 L8,6 Z" fill="#333"/>
    </marker>
    <marker id="arr-light" markerWidth="8" markerHeight="6" refX="8" refY="3" orient="auto">
      <path d="M0,0 L8,3 L0,6 Z" fill="#999"/>
    </marker>
  </defs>

  <!-- White background (required — SVG has no default bg) -->
  <rect width="100%" height="100%" fill="#fff"/>

  <!-- Content goes here -->
  <!-- Use ALL CAPS for every <text> element -->

</svg>
```

## Checklist Before Delivering

- [ ] Can I state the figure's purpose in one sentence?
- [ ] Is the SVG well-formed and renders correctly?
- [ ] Is ALL text in UPPERCASE monospace? (Most distinctive style element)
- [ ] Does the color palette use soft pastels with semantic meaning?
- [ ] Are fills flat pastels by default, with hatching only for legacy/disabled items?
- [ ] Does size encode relative magnitude where applicable?
- [ ] Are labels placed in margins with leader lines (not on top of content)?
- [ ] **Does any text overlap any line, shape, or other text?** (Check carefully)
- [ ] Is the aspect ratio wide (~2:1) with generous padding?
- [ ] Are physical objects drawn as they actually look (curves, cross-sections)?
- [ ] Is there a legend if the figure uses color categories?
- [ ] Have I removed every decorative element that doesn't serve comprehension?
- [ ] Do shape strokes use darker shades of their fill (not uniform `#333` black outlines)?
- [ ] No shadows, no gradients (except for representing actual light/color), no rounded panels?
