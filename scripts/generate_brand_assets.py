from pathlib import Path
from PIL import Image, ImageChops


ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res"
EXTERNAL_SOURCE = Path(r"C:\Users\12131\Desktop\55817cb1-7672-4712-9139-0cc4b61c2caf.png")
PROJECT_SOURCE = RES / "drawable-nodpi" / "logo_lianai_app.webp"
SOURCE = EXTERNAL_SOURCE if EXTERNAL_SOURCE.exists() else PROJECT_SOURCE


def cover_square(image: Image.Image, size: int) -> Image.Image:
    width, height = image.size
    edge = min(width, height)
    left = (width - edge) // 2
    top = (height - edge) // 2
    return image.crop((left, top, left + edge, top + edge)).resize(
        (size, size), Image.Resampling.LANCZOS
    )


def save_webp(image: Image.Image, path: Path, quality: int = 84) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, "WEBP", quality=quality, method=6)


source = Image.open(SOURCE).convert("RGBA")

# App-internal brand mark: enough resolution for profile cards and splash rendering.
save_webp(cover_square(source, 512), RES / "drawable-nodpi" / "logo_lianai_app.webp", 86)

# Legacy launcher assets. Android chooses the nearest density, so no oversized source
# is duplicated into the APK.
launcher_sizes = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}
for folder, size in launcher_sizes.items():
    icon = cover_square(source, size)
    save_webp(icon, RES / folder / "ic_launcher.webp", 84)
    save_webp(icon, RES / folder / "ic_launcher_round.webp", 84)

# Adaptive icons separate the supplied mark into a clean brand-red background and
# a rabbit foreground so Android does not scale or duplicate the rabbit under masks.
background = Image.new("RGBA", (432, 432), (255, 62, 62, 255))
save_webp(background, RES / "drawable-nodpi" / "ic_launcher_background.webp", 82)

rgb = source.convert("RGB")
r, g, b = rgb.split()
white_score = ImageChops.lighter(g, b)
mask = white_score.point(lambda value: max(0, min(255, round((value - 105) * 2.2))))
bbox = mask.getbbox()
if bbox is None:
    raise RuntimeError("Unable to isolate the white rabbit from the supplied logo")

rabbit = source.crop(bbox)
rabbit_mask = mask.crop(bbox)
target = 240
scale = min(target / rabbit.width, target / rabbit.height)
size = (max(1, round(rabbit.width * scale)), max(1, round(rabbit.height * scale)))
rabbit = rabbit.resize(size, Image.Resampling.LANCZOS)
rabbit_mask = rabbit_mask.resize(size, Image.Resampling.LANCZOS)
rabbit.putalpha(rabbit_mask)
foreground = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
foreground.alpha_composite(rabbit, ((432 - size[0]) // 2, (432 - size[1]) // 2))
save_webp(foreground, RES / "drawable-nodpi" / "ic_launcher_foreground.webp", 88)

print("Generated optimized Android brand assets from", SOURCE)
