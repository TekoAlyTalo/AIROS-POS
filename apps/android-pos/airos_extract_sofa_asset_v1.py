from __future__ import annotations

import argparse
from pathlib import Path

try:
    from PIL import Image, ImageFilter
except ImportError as exc:
    raise SystemExit(
        "Missing Pillow. Install once with:\n"
        "  py -m pip install --upgrade pillow\n"
        "Then run this script again."
    ) from exc


def clamp(v: int) -> int:
    return max(0, min(255, v))


def make_sofa_mask(img: Image.Image) -> Image.Image:
    """Build alpha mask for warm brown sofa pixels.

    This intentionally targets the accepted sofa concept:
    warm brown leather furniture on a cool dark blue/gray background.
    """
    rgb = img.convert("RGB")
    w, h = rgb.size
    mask = Image.new("L", (w, h), 0)
    out = mask.load()
    px = rgb.load()

    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]

            warm = (r > g * 1.08 and g > b * 1.05 and r > b * 1.45)
            brown_body = warm and r > 45 and g > 25
            leather_highlight = warm and r > 95 and g > 55
            dark_feet = (y > h * 0.60 and r > 20 and r < 85 and g > 12 and g < 65 and b < 55 and r >= g * 0.80)

            if brown_body or leather_highlight or dark_feet:
                out[x, y] = 255

    # Close small holes and soften antialias.
    mask = mask.filter(ImageFilter.MaxFilter(7))
    mask = mask.filter(ImageFilter.MinFilter(5))
    mask = mask.filter(ImageFilter.GaussianBlur(1.1))

    return mask


def trim_to_content(img: Image.Image, alpha: Image.Image, padding: int) -> tuple[Image.Image, Image.Image]:
    bbox = alpha.getbbox()
    if bbox is None:
        raise SystemExit("Could not detect sofa from input image.")

    left, top, right, bottom = bbox
    left = max(0, left - padding)
    top = max(0, top - padding)
    right = min(img.width, right + padding)
    bottom = min(img.height, bottom + padding)
    box = (left, top, right, bottom)
    return img.crop(box), alpha.crop(box)


def add_soft_shadow(alpha: Image.Image) -> Image.Image:
    """Create a soft shadow behind the sofa in transparent PNG."""
    w, h = alpha.size
    shadow = Image.new("L", (w, h), 0)

    # Shadow comes from sofa silhouette, shifted slightly down.
    shifted = Image.new("L", (w, h), 0)
    shifted.paste(alpha, (0, int(h * 0.045)))
    shifted = shifted.filter(ImageFilter.GaussianBlur(max(8, int(min(w, h) * 0.035))))

    # Limit strength.
    shadow_px = shifted.load()
    for y in range(h):
        for x in range(w):
            shadow_px[x, y] = int(shadow_px[x, y] * 0.36)

    # Put shadow behind sofa, but sofa itself remains opaque.
    combined = Image.new("L", (w, h), 0)
    combined = Image.composite(alpha, shifted, alpha)
    combined_px = combined.load()
    alpha_px = alpha.load()
    shifted_px = shifted.load()

    for y in range(h):
        for x in range(w):
            combined_px[x, y] = max(alpha_px[x, y], shifted_px[x, y])

    return combined


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, help="Accepted sofa target PNG")
    parser.add_argument("--output", required=True, help="Transparent asset PNG output")
    parser.add_argument("--padding", type=int, default=80)
    args = parser.parse_args()

    input_path = Path(args.input)
    output_path = Path(args.output)

    if not input_path.exists():
        raise SystemExit(f"Input not found: {input_path}")

    src = Image.open(input_path).convert("RGBA")

    sofa_alpha = make_sofa_mask(src)
    trimmed_src, trimmed_alpha = trim_to_content(src, sofa_alpha, padding=args.padding)
    final_alpha = add_soft_shadow(trimmed_alpha)

    result = Image.new("RGBA", trimmed_src.size, (0, 0, 0, 0))
    src_rgb = trimmed_src.convert("RGBA")
    result.paste(src_rgb, (0, 0), trimmed_alpha)

    # Add synthetic shadow under sofa where original pixels are transparent.
    shadow_layer = Image.new("RGBA", trimmed_src.size, (0, 0, 0, 0))
    shadow_alpha = Image.new("L", trimmed_src.size, 0)
    shadow_px = shadow_alpha.load()
    final_px = final_alpha.load()
    sofa_px = trimmed_alpha.load()
    for y in range(trimmed_src.height):
        for x in range(trimmed_src.width):
            shadow_px[x, y] = max(0, final_px[x, y] - sofa_px[x, y])
    shadow_layer.putalpha(shadow_alpha)
    shadow_color = Image.new("RGBA", trimmed_src.size, (0, 0, 0, 145))
    shadow_layer = Image.composite(shadow_color, Image.new("RGBA", trimmed_src.size, (0, 0, 0, 0)), shadow_alpha)

    combined = Image.alpha_composite(shadow_layer, result)

    output_path.parent.mkdir(parents=True, exist_ok=True)
    combined.save(output_path)

    print(f"Wrote transparent sofa asset: {output_path}")
    print(f"Size: {combined.width}x{combined.height}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
