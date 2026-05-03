from __future__ import annotations

import argparse
import shutil
from pathlib import Path

try:
    from PIL import Image, ImageFilter
except ImportError as exc:
    raise SystemExit(
        "Missing Pillow. Install once with:\n"
        "  py -m pip install --upgrade pillow\n"
        "Then run this script again."
    ) from exc


def _repo_root() -> Path:
    return Path(__file__).resolve().parent


def _clamp_alpha(value: int) -> int:
    return max(0, min(255, value))


def build_sofa_body_mask(src: Image.Image) -> Image.Image:
    """Detect the warm brown sofa pixels from the approved target image.

    The mask intentionally avoids the cool dark background and any rectangular
    background/shadow panel. The script then creates a fresh sofa-shaped shadow
    from the sofa silhouette, so the output PNG has no rectangular backdrop.
    """
    rgb = src.convert("RGB")
    w, h = rgb.size
    mask = Image.new("L", (w, h), 0)
    mask_px = mask.load()
    px = rgb.load()

    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]

            # Brown leather family: red channel clearly leads, green stays above blue.
            warm_brown = r > 42 and g > 22 and r > g * 1.04 and g > b * 1.02 and r > b * 1.32

            # Leather highlights can be lighter/less saturated but remain warm.
            leather_highlight = r > 82 and g > 48 and b < 78 and r > g * 1.02 and r > b * 1.22

            # Dark feet/edge pixels are allowed only near already warm furniture tones.
            dark_warm_edge = r > 24 and g > 14 and b < 44 and r >= g * 0.90 and g >= b * 0.85

            if warm_brown or leather_highlight or dark_warm_edge:
                mask_px[x, y] = 255

    # Close holes inside cushions/arms and soften the antialias edge.
    mask = mask.filter(ImageFilter.MaxFilter(9))
    mask = mask.filter(ImageFilter.MinFilter(5))
    mask = mask.filter(ImageFilter.GaussianBlur(1.0))
    return mask


def trim_to_alpha(img: Image.Image, padding: int) -> Image.Image:
    alpha = img.getchannel("A")
    bbox = alpha.getbbox()
    if bbox is None:
        raise SystemExit("Output alpha is empty; sofa was not detected.")
    left, top, right, bottom = bbox
    left = max(0, left - padding)
    top = max(0, top - padding)
    right = min(img.width, right + padding)
    bottom = min(img.height, bottom + padding)
    return img.crop((left, top, right, bottom))


def make_clean_sofa_asset(input_path: Path, output_paths: list[Path], padding: int) -> None:
    if not input_path.exists():
        raise SystemExit(f"Target image not found: {input_path}")

    src = Image.open(input_path).convert("RGBA")
    sofa_mask = build_sofa_body_mask(src)

    # Fresh organic shadow from sofa silhouette only. No background rectangle survives.
    shadow_mask = Image.new("L", src.size, 0)
    shadow_offset_y = max(4, int(src.height * 0.030))
    shadow_mask.paste(sofa_mask, (0, shadow_offset_y))
    shadow_blur = max(10, int(min(src.size) * 0.030))
    shadow_mask = shadow_mask.filter(ImageFilter.GaussianBlur(shadow_blur))

    shadow_strength = 115
    shadow_px = shadow_mask.load()
    sofa_px = sofa_mask.load()
    for y in range(src.height):
        for x in range(src.width):
            # Keep shadow soft, and do not darken pixels under the sofa body itself.
            shadow_px[x, y] = _clamp_alpha(int(shadow_px[x, y] * shadow_strength / 255))
            if sofa_px[x, y] > 12:
                shadow_px[x, y] = min(shadow_px[x, y], 20)

    shadow_layer = Image.new("RGBA", src.size, (0, 0, 0, 0))
    shadow_color = Image.new("RGBA", src.size, (0, 0, 0, 160))
    shadow_layer = Image.composite(shadow_color, shadow_layer, shadow_mask)

    sofa_layer = Image.new("RGBA", src.size, (0, 0, 0, 0))
    sofa_layer.paste(src, (0, 0), sofa_mask)

    combined = Image.alpha_composite(shadow_layer, sofa_layer)
    combined = trim_to_alpha(combined, padding=padding)

    for output_path in output_paths:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        combined.save(output_path)
        print(f"Wrote {output_path} ({combined.width}x{combined.height})")


def patch_tablemap(tablemap_path: Path) -> None:
    if not tablemap_path.exists():
        raise SystemExit(f"FloorPlanTableMap.kt not found: {tablemap_path}")

    text = tablemap_path.read_text(encoding="utf-8")
    original = text

    resource_old = "R.drawable.sofa2_premium_topdown_asset_v1"
    resource_new = "R.drawable.sofa2_premium_topdown_asset_v2"
    if resource_old not in text and resource_new not in text:
        raise SystemExit("Could not find sofa asset resource reference in FloorPlanTableMap.kt")
    text = text.replace(resource_old, resource_new)

    # Important: FillBounds means the asset uses the floor-plan object footprint.
    # The physical size truth stays in the floor-plan object dimensions, not in PNG magic scaling.
    text = text.replace(
        "contentScale = ContentScale.Fit,",
        "contentScale = ContentScale.FillBounds,",
        1,
    )

    if text == original:
        print("FloorPlanTableMap.kt already looked patched; no text change needed.")
        return

    backup_path = tablemap_path.with_suffix(tablemap_path.suffix + ".bak_sofa_asset_v2")
    if not backup_path.exists():
        shutil.copy2(tablemap_path, backup_path)
        print(f"Backup written: {backup_path}")

    tablemap_path.write_text(text, encoding="utf-8", newline="\n")
    print(f"Patched {tablemap_path}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Apply AIROS sofa asset v2 proof fix.")
    parser.add_argument(
        "--target",
        default=r"C:\AIROS compare\shared\sofa2_premium_topdown_target_v1.png",
        help="Approved high-resolution sofa target image.",
    )
    parser.add_argument(
        "--padding",
        type=int,
        default=72,
        help="Transparent padding around the generated sofa asset.",
    )
    args = parser.parse_args()

    root = _repo_root()
    tablemap_path = root / "feature" / "tablemap" / "src" / "main" / "kotlin" / "com" / "airos" / "pos" / "feature" / "tablemap" / "FloorPlanTableMap.kt"
    feature_asset = root / "feature" / "tablemap" / "src" / "main" / "res" / "drawable-nodpi" / "sofa2_premium_topdown_asset_v2.png"
    core_asset = root / "core" / "ui" / "src" / "main" / "res" / "drawable-nodpi" / "sofa2_premium_topdown_asset_v2.png"

    make_clean_sofa_asset(
        input_path=Path(args.target),
        output_paths=[feature_asset, core_asset],
        padding=args.padding,
    )
    patch_tablemap(tablemap_path)

    print("Done. Scope: sofa asset alpha/shadow + TableMap asset resource/content scale only.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
