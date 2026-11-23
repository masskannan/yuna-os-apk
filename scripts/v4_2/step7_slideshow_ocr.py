"""
YUNA AI OS v4.2 - Step 7: Automated Slideshow Video + Scan OCR (CPU Worker)
==========================================================================
Runs on GitHub Actions (ubuntu-latest CPU) and supports two modes:
  1. --mode slideshow : JSON -> Tamil TTS (edge-tts) -> MP4 + burned-in SRT subtitles
  2. --mode ocr       : Scanned Image/PDF -> PaddleOCR (CPU) -> Page-indexed Markdown JSON
"""

import argparse
import asyncio
import json
import os
import subprocess
import tempfile
from pathlib import Path
from typing import Any, Dict, List

# WHY: edge-tts is free, requires no API key or GPU, and provides natural Tamil
# neural voices (like ta-SG-VenbaNeural) over Microsoft's Edge TTS service.
import edge_tts

# WHY: Pillow normalizes slide images (RGB conversion + even dimensions) before
# video encoding, preventing libx264 crashes on odd-pixel widths/heights.
from PIL import Image


def format_srt_timestamp(seconds: float) -> str:
    """Convert floating-point seconds to standard SRT format: HH:MM:SS,mmm."""
    total_ms = max(0, int(round(seconds * 1000)))
    hours, rem = divmod(total_ms, 3_600_000)
    minutes, rem = divmod(rem, 60_000)
    secs, ms = divmod(rem, 1000)
    return f"{hours:02d}:{minutes:02d}:{secs:02d},{ms:03d}"


def normalize_slide_image(src_path: str, dst_path: str, size: tuple[int, int] = (1280, 720)) -> str:
    """
    Resize and pad any slide image to a uniform 1280x720 RGB frame.
    WHY: Video encoders (H.264 / yuv420p) require even width/height dimensions, and
    concatenating slides of mixed resolutions causes distortion or encoding errors.
    """
    with Image.open(src_path) as img:
        rgb_img = img.convert("RGB")
        rgb_img.thumbnail(size, Image.Resampling.LANCZOS)

        # WHY: Letterbox onto a dark slate background so aspect ratio is preserved
        # without cropping important diagram or text details.
        canvas = Image.new("RGB", size, (15, 23, 42))
        offset_x = (size[0] - rgb_img.width) // 2
        offset_y = (size[1] - rgb_img.height) // 2
        canvas.paste(rgb_img, (offset_x, offset_y))
        canvas.save(dst_path, format="PNG")
    return dst_path


async def synthesize_tts_audio(text: str, voice: str, out_mp3: str) -> None:
    """
    Generate speech audio for a single slide using edge-tts.
    WHY: edge-tts is async-only because it streams audio chunks over a WebSocket.
    """
    communicate = edge_tts.Communicate(text=text, voice=voice)
    await communicate.save(out_mp3)


def build_slideshow(input_json_path: str, output_mp4_path: str) -> None:
    """
    Build an MP4 slideshow from JSON {script_slides: [{text, image_path}], lang: "ta"}
    with Tamil edge-tts narration and burned-in SRT subtitles.
    """
    # WHY: Import moviepy lazily inside slideshow mode so OCR-only runs don't
    # fail or spend startup time loading video/audio libraries.
    try:
        # MoviePy 2.x import style
        from moviepy import AudioFileClip, ImageClip, concatenate_videoclips
        is_moviepy_v2 = True
    except ImportError:
        # Fallback for MoviePy 1.x
        from moviepy.editor import AudioFileClip, ImageClip, concatenate_videoclips
        is_moviepy_v2 = False

    with open(input_json_path, "r", encoding="utf-8") as f:
        payload = json.load(f)

    slides = payload.get("script_slides", [])
    if not slides:
        raise ValueError("Input JSON must contain a non-empty 'script_slides' list.")

    lang = payload.get("lang", "ta").lower()
    # WHY: Default to ta-SG-VenbaNeural as requested for natural Tamil speech;
    # fall back to an English neural voice only if lang is explicitly 'en'.
    voice = "ta-SG-VenbaNeural" if lang.startswith("ta") else "en-IN-NeerjaNeural"

    output_path = Path(output_mp4_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="yuna_slideshow_") as tmp_dir:
        tmp = Path(tmp_dir)
        clips = []
        audio_clips = []
        srt_entries: List[str] = []
        current_time = 0.0

        for idx, slide in enumerate(slides, start=1):
            text = (slide.get("text") or "").strip()
            image_path = slide.get("image_path")
            if not text or not image_path:
                raise ValueError(f"Slide {idx} is missing required 'text' or 'image_path'.")
            if not Path(image_path).exists():
                raise FileNotFoundError(f"Slide {idx} image not found: {image_path}")

            audio_file = str(tmp / f"slide_{idx:03d}.mp3")
            norm_image = str(tmp / f"slide_{idx:03d}.png")

            # 1. Generate narration audio
            asyncio.run(synthesize_tts_audio(text, voice, audio_file))

            # 2. Normalize image frame
            normalize_slide_image(image_path, norm_image)

            # 3. Measure exact audio duration so the slide stays synced with speech
            audio_clip = AudioFileClip(audio_file)
            duration = float(audio_clip.duration)
            audio_clips.append(audio_clip)

            # 4. Build video clip for this slide
            img_clip = ImageClip(norm_image)
            if is_moviepy_v2:
                slide_clip = img_clip.with_duration(duration).with_audio(audio_clip)
            else:
                slide_clip = img_clip.set_duration(duration).set_audio(audio_clip)
            clips.append(slide_clip)

            # 5. Record SRT subtitle entry aligned to this slide's time window
            start_ts = format_srt_timestamp(current_time)
            end_ts = format_srt_timestamp(current_time + duration)
            srt_entries.append(f"{idx}\n{start_ts} --> {end_ts}\n{text}\n")
            current_time += duration

        # Write temporary base MP4 (without subtitles) and SRT file
        base_mp4 = str(tmp / "base_slideshow.mp4")
        srt_path = str(tmp / "subtitles.srt")
        with open(srt_path, "w", encoding="utf-8") as srt_f:
            srt_f.write("\n".join(srt_entries))

        final_clip = concatenate_videoclips(clips, method="compose")
        # WHY: fps=24 and libx264 + aac produce universally playable MP4 files
        # while keeping CPU encoding time fast on GitHub Actions.
        final_clip.write_videofile(
            base_mp4,
            fps=24,
            codec="libx264",
            audio_codec="aac",
            logger=None,
        )

        # Explicitly close clips to release file handles before ffmpeg runs
        final_clip.close()
        for clip in clips:
            clip.close()
        for a_clip in audio_clips:
            a_clip.close()

        # WHY: We burn subtitles using ffmpeg's libass filter instead of MoviePy
        # TextClip because libass uses HarfBuzz/Pango to properly shape Tamil
        # vowel signs and ligatures, and doesn't require ImageMagick.
        # Note: ffmpeg filter paths must escape colons/backslashes if any.
        escaped_srt = srt_path.replace("\\", "/").replace(":", "\\:")
        subtitle_style = (
            "FontName=Noto Sans Tamil,FontSize=20,PrimaryColour=&H00FFFFFF,"
            "OutlineColour=&H00000000,BackColour=&H80000000,BorderStyle=3,Outline=1,MarginV=24"
        )
        ffmpeg_cmd = [
            "ffmpeg",
            "-y",
            "-i",
            base_mp4,
            "-vf",
            f"subtitles={escaped_srt}:force_style='{subtitle_style}'",
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-pix_fmt",
            "yuv420p",
            "-c:a",
            "copy",
            "-movflags",
            "+faststart",
            str(output_path),
        ]
        subprocess.run(ffmpeg_cmd, check=True)
        print(f"[slideshow] Generated MP4 with burned-in subtitles: {output_path}")


def extract_ocr_lines(ocr_result: Any) -> List[str]:
    """
    Parse PaddleOCR output across v2.x and v3.x return structures into clean text lines.
    WHY: PaddleOCR's nested list format varies slightly by version; normalizing it here
    keeps the Markdown builder clean and resilient.
    """
    lines: List[str] = []
    if not ocr_result:
        return lines

    # PaddleOCR v2.x returns: [ [ [box, (text, confidence)], ... ] ]
    page_items = ocr_result[0] if isinstance(ocr_result, list) and len(ocr_result) > 0 else ocr_result
    if isinstance(page_items, list):
        for item in page_items:
            if isinstance(item, (list, tuple)) and len(item) >= 2:
                text_info = item[1]
                if isinstance(text_info, (list, tuple)) and len(text_info) >= 1:
                    text = str(text_info[0]).strip()
                    if text:
                        lines.append(text)
    elif isinstance(page_items, dict) and "rec_texts" in page_items:
        # PaddleOCR v3.x dictionary-style output
        for text in page_items["rec_texts"]:
            cleaned = str(text).strip()
            if cleaned:
                lines.append(cleaned)

    return lines


def run_ocr_pipeline(input_path: str, output_json_path: str, ocr_lang: str = "ta") -> None:
    """
    Run PaddleOCR (CPU) on a scanned Tamil/English image or PDF and output
    page-indexed Markdown JSON ready for RAG ingestion.
    """
    # WHY: Import PaddleOCR lazily so slideshow-only jobs don't load PaddlePaddle.
    from paddleocr import PaddleOCR

    src = Path(input_path)
    if not src.exists():
        raise FileNotFoundError(f"OCR input file not found: {src}")

    # WHY: use_gpu=False forces CPU execution on GitHub Actions runners (no GPU).
    # lang='ta' loads the Tamil model, which also handles English letters and digits.
    ocr_engine = PaddleOCR(use_angle_cls=True, lang=ocr_lang, use_gpu=False, show_log=False)

    pages_output: List[Dict[str, Any]] = []

    with tempfile.TemporaryDirectory(prefix="yuna_ocr_") as tmp_dir:
        tmp = Path(tmp_dir)
        image_paths: List[Path] = []

        if src.suffix.lower() == ".pdf":
            # WHY: Convert PDF pages to 200 DPI PNGs so PaddleOCR can read scanned
            # PDFs (which have no embedded text layer) without exhausting runner RAM.
            from pdf2image import convert_from_path

            pil_pages = convert_from_path(str(src), dpi=200)
            for page_idx, pil_img in enumerate(pil_pages, start=1):
                img_file = tmp / f"page_{page_idx:04d}.png"
                pil_img.save(str(img_file), format="PNG")
                image_paths.append(img_file)
        else:
            image_paths.append(src)

        for page_num, img_file in enumerate(image_paths, start=1):
            result = ocr_engine.ocr(str(img_file), cls=True)
            lines = extract_ocr_lines(result)
            body_md = "\n\n".join(lines) if lines else "_No readable text detected._"
            page_markdown = f"## Page {page_num}\n\n{body_md}"

            pages_output.append(
                {
                    "page": page_num,
                    "markdown": page_markdown,
                    "line_count": len(lines),
                }
            )

    rag_payload = {
        "source_file": src.name,
        "lang": ocr_lang,
        "total_pages": len(pages_output),
        "pages": pages_output,
    }

    out_path = Path(output_json_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(rag_payload, f, ensure_ascii=False, indent=2)

    print(f"[ocr] Processed {len(pages_output)} page(s) -> {out_path}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="YUNA AI OS v4.2 Step 7: Slideshow Video Generator & Scan OCR"
    )
    parser.add_argument(
        "--mode",
        required=True,
        choices=["slideshow", "ocr"],
        help="Pipeline mode: 'slideshow' (JSON -> MP4) or 'ocr' (Image/PDF -> RAG JSON)",
    )
    parser.add_argument(
        "--input",
        required=True,
        help="Path to input JSON (slideshow) or scanned Image/PDF (ocr)",
    )
    parser.add_argument(
        "--output",
        required=True,
        help="Path to output MP4 (slideshow) or Markdown JSON (ocr)",
    )
    parser.add_argument(
        "--ocr-lang",
        default="ta",
        help="PaddleOCR language code (default: 'ta' for Tamil + English)",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.mode == "slideshow":
        build_slideshow(args.input, args.output)
    elif args.mode == "ocr":
        run_ocr_pipeline(args.input, args.output, ocr_lang=args.ocr_lang)


if __name__ == "__main__":
    main()
