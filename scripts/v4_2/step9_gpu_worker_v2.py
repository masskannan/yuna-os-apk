"""
YUNA AI OS v4.2 — Step 9: Semi-Manual Kaggle / Colab GPU Polling Worker
=======================================================================
Meant to be run manually by the user inside an interactive Kaggle or Google
Colab notebook (personal, interactive use only — respects platform ToS).

Lifecycle:
  1. Polls POST /api/gpu/claim with X-Yuna-Gpu-Secret to atomically claim
     one `pending` job and receive a presigned Cloudflare R2 PUT URL.
  2. Dispatches the job by `job_type`:
       - `wan2gp_i2v`          -> Wan 2.1 1.3B Image-to-Video (5-10s clip)
       - `comfyui_flux_schnell`-> Headless ComfyUI (/prompt + /history)
       - `indic_parler_tts`    -> AI4Bharat Indic Parler-TTS (Tamil batch TTS)
  3. Streams the generated media file directly to the presigned R2 URL,
     calls POST /api/gpu/jobs/{id}/complete (which triggers the FCM push
     "வீடியோ தயார்"), and cleans up temporary files.
  4. Exits cleanly as soon as the queue returns HTTP 204 (empty) OR when
     total runtime reaches 1800 seconds (~30 minutes).
"""

from __future__ import annotations

import json
import logging
import mimetypes
import os
import subprocess
import sys
import time
import urllib.parse
from pathlib import Path
from typing import Any, Callable

import requests

# ---------------------------------------------------------------------------
# 1. CONFIGURATION & SECRETS
# ---------------------------------------------------------------------------
# WHY: Keeping all tunable settings at the top lets you override them with
# environment variables in Colab/Kaggle without editing the worker logic.
DEFAULT_WORKER_URL = "https://yuna-ai-gateway-v4.masskannan719.workers.dev"
YUNA_WORKER_URL = os.getenv("YUNA_WORKER_URL", DEFAULT_WORKER_URL).rstrip("/")

# WHY: 1800 seconds (~30 minutes) acts as a hard safety ceiling so an
# interactive Kaggle/Colab session never runs unattended past your intended
# window, keeping usage compliant with personal interactive-use ToS.
MAX_RUNTIME_SECONDS = int(os.getenv("YUNA_MAX_RUNTIME_SECONDS", "1800"))

# WHY: Short HTTP timeouts prevent a stalled network socket from hanging the
# entire 30-minute budget, while upload/inference timeouts stay generous.
API_TIMEOUT_SECONDS = 30
UPLOAD_TIMEOUT_SECONDS = 300
COMFYUI_URL = os.getenv("COMFYUI_URL", "http://127.0.0.1:8188").rstrip("/")
WAN2GP_DIR = Path(os.getenv("WAN2GP_DIR", "/kaggle/working/Wan2GP"))
OUTPUT_DIR = Path(os.getenv("YUNA_OUTPUT_DIR", "/tmp/yuna_gpu_outputs"))

# WHY: Setting YUNA_DRY_RUN=1 lets you test the Worker -> R2 -> FCM pipeline
# end-to-end with tiny dummy files before downloading multi-GB model weights.
DRY_RUN = os.getenv("YUNA_DRY_RUN", "0") == "1"

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("yuna.step9")


def load_gpu_secret() -> str:
    """Resolve X-Yuna-Gpu-Secret from env vars, Colab userdata, or Kaggle Secrets."""
    # WHY: Checking standard env vars first, then Colab/Kaggle secret stores,
    # means you never have to hardcode your secret inside a notebook cell.
    secret = os.getenv("YUNA_GPU_SECRET", "").strip()
    if secret:
        return secret

    try:
        from google.colab import userdata  # type: ignore

        secret = (userdata.get("YUNA_GPU_SECRET") or "").strip()
        if secret:
            return secret
    except Exception:
        pass

    try:
        from kaggle_secrets import UserSecretsClient  # type: ignore

        secret = (UserSecretsClient().get_secret("YUNA_GPU_SECRET") or "").strip()
        if secret:
            return secret
    except Exception:
        pass

    raise RuntimeError(
        "YUNA_GPU_SECRET not found. Set it via os.environ['YUNA_GPU_SECRET'], "
        "Colab Secrets, or Kaggle User Secrets before running."
    )


# ---------------------------------------------------------------------------
# 2. CLOUDFLARE WORKER API HELPERS
# ---------------------------------------------------------------------------
def build_headers(secret: str) -> dict[str, str]:
    """Attach the shared GPU worker authentication header."""
    return {
        "X-Yuna-Gpu-Secret": secret,
        "Content-Type": "application/json",
    }


def claim_next_job(session: requests.Session, secret: str) -> dict[str, Any] | None:
    """
    Atomically claim one pending job from the Cloudflare Worker.
    Returns the job dict on 200 OK, or None when the queue is empty (204).
    """
    url = f"{YUNA_WORKER_URL}/api/gpu/claim"
    # WHY: Sending a lightweight worker_id helps Cloudflare D1 logs trace
    # which manual notebook session processed a given job.
    body = {
        "worker_id": os.getenv("HOSTNAME", "kaggle-colab-manual"),
        "supported_types": [
            "wan2gp_i2v",
            "comfyui_flux_schnell",
            "indic_parler_tts",
        ],
    }

    resp = session.post(
        url,
        headers=build_headers(secret),
        json=body,
        timeout=API_TIMEOUT_SECONDS,
    )

    if resp.status_code == 204:
        # WHY: HTTP 204 means the D1 queue has zero pending jobs right now,
        # so the worker should shut down cleanly instead of idling on a GPU.
        return None

    if resp.status_code in (401, 403):
        raise PermissionError(
            f"Worker rejected X-Yuna-Gpu-Secret (HTTP {resp.status_code})."
        )

    resp.raise_for_status()
    data = resp.json()
    # WHY: Workers sometimes wrap the claimed row under {"job": {...}};
    # unwrapping here keeps downstream handlers clean.
    return data.get("job", data)


def upload_to_presigned_r2(
    session: requests.Session,
    presigned_put_url: str,
    file_path: Path,
    content_type: str,
) -> int:
    """Stream the generated file directly to R2 via the presigned PUT URL."""
    file_size = file_path.stat().st_size
    # WHY: Streaming from an open binary file avoids loading large 1080p/MP4
    # outputs into RAM, preventing OOM crashes on 12-16 GB Colab/Kaggle VMs.
    with file_path.open("rb") as fh:
        resp = session.put(
            presigned_put_url,
            data=fh,
            headers={"Content-Type": content_type},
            timeout=UPLOAD_TIMEOUT_SECONDS,
        )
    resp.raise_for_status()
    return file_size


def mark_job_complete(
    session: requests.Session,
    secret: str,
    job_id: str,
    metadata: dict[str, Any],
) -> dict[str, Any]:
    """
    Notify the Cloudflare Worker that R2 upload succeeded.
    The Worker marks D1 status='completed' and triggers FCM push "வீடியோ தயார்".
    """
    url = f"{YUNA_WORKER_URL}/api/gpu/jobs/{urllib.parse.quote(job_id)}/complete"
    resp = session.post(
        url,
        headers=build_headers(secret),
        json=metadata,
        timeout=API_TIMEOUT_SECONDS,
    )
    resp.raise_for_status()
    return resp.json() if resp.content else {"status": "completed"}


def report_job_failure(
    session: requests.Session,
    secret: str,
    job_id: str,
    error_message: str,
) -> None:
    """Best-effort failure callback so a broken job does not stay stuck in 'claimed'."""
    # WHY: If inference crashes, telling the Worker lets it release or mark
    # the job failed instead of leaving it stranded until a lease timeout.
    url = f"{YUNA_WORKER_URL}/api/gpu/jobs/{urllib.parse.quote(job_id)}/fail"
    try:
        session.post(
            url,
            headers=build_headers(secret),
            json={"error": error_message[:500]},
            timeout=API_TIMEOUT_SECONDS,
        )
    except Exception as exc:
        log.warning("Could not report failure for job %s: %s", job_id, exc)


# ---------------------------------------------------------------------------
# 3. JOB HANDLERS (DISPATCHED BY job_type)
# ---------------------------------------------------------------------------
def _download_input_file(
    session: requests.Session, url: str, dest_path: Path
) -> Path:
    """Download a reference image or asset needed by an inference job."""
    dest_path.parent.mkdir(parents=True, exist_ok=True)
    with session.get(url, stream=True, timeout=60) as resp:
        resp.raise_for_status()
        with dest_path.open("wb") as fh:
            for chunk in resp.iter_content(chunk_size=1024 * 1024):
                if chunk:
                    fh.write(chunk)
    return dest_path


def run_wan2gp_i2v(
    session: requests.Session, job_id: str, payload: dict[str, Any]
) -> Path:
    """
    Generate a 5-10s video clip using Wan 2.1 1.3B via Wan2GP.
    Expected payload keys: prompt, image_url (optional), duration_sec (5-10), seed.
    """
    output_path = OUTPUT_DIR / f"{job_id}_wan21.mp4"
    output_path.parent.mkdir(parents=True, exist_ok=True)

    if DRY_RUN:
        output_path.write_bytes(b"\x00\x00\x00\x18ftypmp42\x00\x00\x00\x00mp42isom")
        return output_path

    prompt = str(payload.get("prompt", "Cinematic slow camera pan, natural lighting"))
    # WHY: Clamping duration between 5 and 10 seconds matches Wan 2.1 1.3B's
    # sweet spot on 15-16 GB T4/P100 GPUs without triggering CUDA OOM.
    duration_sec = max(5, min(10, int(payload.get("duration_sec", 5))))
    fps = int(payload.get("fps", 16))
    num_frames = duration_sec * fps + 1

    input_image_path: Path | None = None
    if payload.get("image_url"):
        input_image_path = _download_input_file(
            session, str(payload["image_url"]), OUTPUT_DIR / f"{job_id}_ref.png"
        )

    wgp_script = WAN2GP_DIR / "wgp.py"
    if not wgp_script.exists():
        raise FileNotFoundError(
            f"Wan2GP entrypoint not found at {wgp_script}. "
            "Clone Wan2GP or set WAN2GP_DIR."
        )

    # WHY: Running Wan2GP in a subprocess guarantees all GPU VRAM is freed
    # back to the OS as soon as the clip finishes, so the next job (e.g. Flux
    # or Parler-TTS) starts with a clean GPU memory slate.
    cmd = [
        sys.executable,
        str(wgp_script),
        "--model",
        str(payload.get("model", "i2v_1.3B")),
        "--prompt",
        prompt,
        "--frames",
        str(num_frames),
        "--output",
        str(output_path),
    ]
    if input_image_path:
        cmd.extend(["--image", str(input_image_path)])
    if "seed" in payload:
        cmd.extend(["--seed", str(int(payload["seed"]))])

    log.info("Running Wan2GP (%ss, %s frames) for job %s...", duration_sec, num_frames, job_id)
    subprocess.run(cmd, cwd=str(WAN2GP_DIR), check=True)

    if not output_path.exists():
        raise FileNotFoundError(f"Wan2GP finished without creating {output_path}")
    return output_path


def _build_flux_schnell_workflow(payload: dict[str, Any]) -> dict[str, Any]:
    """Construct a minimal headless ComfyUI Flux.1 Schnell API workflow."""
    if isinstance(payload.get("workflow"), dict):
        return payload["workflow"]

    prompt_text = str(payload.get("prompt", "Minimalist Tamil editorial poster"))
    width = int(payload.get("width", 1024))
    height = int(payload.get("height", 1024))
    seed = int(payload.get("seed", int(time.time()) % 2_147_483_647))
    steps = int(payload.get("steps", 4))  # Schnell is distilled for 4 steps

    return {
        "1": {
            "class_type": "UNETLoader",
            "inputs": {
                "unet_name": payload.get("unet_name", "flux1-schnell-fp8.safetensors"),
                "weight_dtype": "fp8_e4m3fn",
            },
        },
        "2": {
            "class_type": "DualCLIPLoader",
            "inputs": {
                "clip_name1": "clip_l.safetensors",
                "clip_name2": "t5xxl_fp8_e4m3fn.safetensors",
                "type": "flux",
            },
        },
        "3": {
            "class_type": "VAELoader",
            "inputs": {"vae_name": "ae.safetensors"},
        },
        "4": {
            "class_type": "CLIPTextEncode",
            "inputs": {"text": prompt_text, "clip": ["2", 0]},
        },
        "5": {
            "class_type": "EmptySD3LatentImage",
            "inputs": {"width": width, "height": height, "batch_size": 1},
        },
        "6": {
            "class_type": "KSampler",
            "inputs": {
                "model": ["1", 0],
                "positive": ["4", 0],
                "negative": ["4", 0],
                "latent_image": ["5", 0],
                "seed": seed,
                "steps": steps,
                "cfg": 1.0,
                "sampler_name": "euler",
                "scheduler": "simple",
                "denoise": 1.0,
            },
        },
        "7": {
            "class_type": "VAEDecode",
            "inputs": {"samples": ["6", 0], "vae": ["3", 0]},
        },
        "8": {
            "class_type": "SaveImage",
            "inputs": {"images": ["7", 0], "filename_prefix": "yuna_flux"},
        },
    }


def run_comfyui_flux_schnell(
    session: requests.Session, job_id: str, payload: dict[str, Any]
) -> Path:
    """
    Submit a Flux.1 Schnell workflow to headless ComfyUI (/prompt) and poll
    /history until the rendered PNG is ready.
    """
    output_path = OUTPUT_DIR / f"{job_id}_flux.png"
    output_path.parent.mkdir(parents=True, exist_ok=True)

    if DRY_RUN:
        # 1x1 transparent PNG header for pipeline testing
        output_path.write_bytes(
            b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01"
            b"\x08\x06\x00\x00\x00\x1f\x15c4\x00\x00\x00\nIDATx\x9cc\x00\x01\x00"
            b"\x00\x05\x00\x01\r\n-\xb4\x00\x00\x00\x00IEND\xaeB`\x82"
        )
        return output_path

    workflow = _build_flux_schnell_workflow(payload)
    queue_resp = session.post(
        f"{COMFYUI_URL}/prompt",
        json={"prompt": workflow, "client_id": f"yuna-{job_id}"},
        timeout=API_TIMEOUT_SECONDS,
    )
    queue_resp.raise_for_status()
    prompt_id = queue_resp.json()["prompt_id"]
    log.info("Queued ComfyUI prompt_id=%s for job %s", prompt_id, job_id)

    # WHY: ComfyUI's /prompt endpoint is asynchronous; polling /history/{id}
    # lets us wait cleanly without needing a WebSocket client.
    image_info: dict[str, str] | None = None
    deadline = time.monotonic() + 300
    while time.monotonic() < deadline:
        hist_resp = session.get(
            f"{COMFYUI_URL}/history/{prompt_id}", timeout=API_TIMEOUT_SECONDS
        )
        hist_resp.raise_for_status()
        history = hist_resp.json()
        if prompt_id in history:
            outputs = history[prompt_id].get("outputs", {})
            for node_out in outputs.values():
                images = node_out.get("images") or []
                if images:
                    image_info = images[0]
                    break
            if image_info:
                break
        time.sleep(2)

    if not image_info:
        raise TimeoutError(f"ComfyUI prompt {prompt_id} did not produce an image.")

    params = urllib.parse.urlencode(
        {
            "filename": image_info["filename"],
            "subfolder": image_info.get("subfolder", ""),
            "type": image_info.get("type", "output"),
        }
    )
    img_resp = session.get(f"{COMFYUI_URL}/view?{params}", timeout=60)
    img_resp.raise_for_status()
    output_path.write_bytes(img_resp.content)
    return output_path


def run_indic_parler_tts(
    session: requests.Session, job_id: str, payload: dict[str, Any]
) -> Path:
    """
    Synthesize Tamil speech (single string or batch list) using
    ai4bharat/indic-parler-tts and concatenate into a single WAV file.
    """
    output_path = OUTPUT_DIR / f"{job_id}_tamil_tts.wav"
    output_path.parent.mkdir(parents=True, exist_ok=True)

    if DRY_RUN:
        # Minimal valid 44-byte silent WAV header for dry-run verification
        output_path.write_bytes(
            b"RIFF$\x00\x00\x00WAVEfmt \x10\x00\x00\x00\x01\x00\x01\x00"
            b"\x80>\x00\x00\x00}\x00\x00\x02\x00\x10\x00data\x00\x00\x00\x00"
        )
        return output_path

    # WHY: Lazy-importing torch/parler_tts inside the handler ensures ComfyUI
    # or Wan2GP jobs don't waste startup time or VRAM loading TTS libraries.
    import numpy as np
    import soundfile as sf
    import torch
    from parler_tts import ParlerTTSForConditionalGeneration
    from transformers import AutoTokenizer

    model_id = str(payload.get("model_id", "ai4bharat/indic-parler-tts"))
    device = "cuda:0" if torch.cuda.is_available() else "cpu"

    texts = payload.get("texts") or [payload.get("text", "வணக்கம், யூனா தயார்.")]
    if isinstance(texts, str):
        texts = [texts]

    description = str(
        payload.get(
            "description",
            "Jaya speaks in a clear, warm Tamil voice with steady pacing and no background noise.",
        )
    )

    log.info("Loading %s on %s for %d Tamil segment(s)...", model_id, device, len(texts))
    model = ParlerTTSForConditionalGeneration.from_pretrained(model_id).to(device)
    tokenizer = AutoTokenizer.from_pretrained(model_id)
    desc_tokenizer = AutoTokenizer.from_pretrained(model.config.text_encoder._name_or_path)

    desc_ids = desc_tokenizer(description, return_tensors="pt").to(device)
    audio_chunks: list[np.ndarray] = []
    pause = np.zeros(int(model.config.sampling_rate * 0.25), dtype=np.float32)

    try:
        for idx, line in enumerate(texts, start=1):
            log.info("Synthesizing Tamil segment %d/%d...", idx, len(texts))
            prompt_ids = tokenizer(str(line), return_tensors="pt").to(device)
            with torch.inference_mode():
                generation = model.generate(
                    input_ids=desc_ids.input_ids,
                    attention_mask=desc_ids.attention_mask,
                    prompt_input_ids=prompt_ids.input_ids,
                    prompt_attention_mask=prompt_ids.attention_mask,
                )
            audio_arr = generation.cpu().numpy().squeeze().astype(np.float32)
            audio_chunks.append(audio_arr)
            audio_chunks.append(pause)

        combined_audio = np.concatenate(audio_chunks)
        sf.write(str(output_path), combined_audio, model.config.sampling_rate)
    finally:
        # WHY: Explicitly deleting the model and emptying CUDA cache frees VRAM
        # immediately in case the next queued job is Wan2GP or Flux.
        del model
        if torch.cuda.is_available():
            torch.cuda.empty_cache()

    return output_path


JOB_DISPATCHERS: dict[str, Callable[[requests.Session, str, dict[str, Any]], Path]] = {
    "wan2gp_i2v": run_wan2gp_i2v,
    "comfyui_flux_schnell": run_comfyui_flux_schnell,
    "indic_parler_tts": run_indic_parler_tts,
}


# ---------------------------------------------------------------------------
# 4. MAIN POLLING LOOP
# ---------------------------------------------------------------------------
def main() -> int:
    secret = load_gpu_secret()
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    session = requests.Session()

    start_time = time.monotonic()
    jobs_completed = 0

    log.info(
        "YUNA Step 9 GPU Worker started | Worker=%s | MaxRuntime=%ss | DryRun=%s",
        YUNA_WORKER_URL,
        MAX_RUNTIME_SECONDS,
        DRY_RUN,
    )

    try:
        while True:
            elapsed = time.monotonic() - start_time
            if elapsed >= MAX_RUNTIME_SECONDS:
                log.info(
                    "Reached %ds runtime cap (~30 min). Exiting cleanly.",
                    MAX_RUNTIME_SECONDS,
                )
                break

            job = claim_next_job(session, secret)
            if job is None:
                log.info("Queue is empty (HTTP 204). Exiting cleanly.")
                break

            job_id = str(job.get("id") or job.get("job_id"))
            job_type = str(job.get("job_type", "")).strip()
            presigned_url = str(
                job.get("presigned_put_url") or job.get("upload_url") or ""
            )
            payload = job.get("payload") or {}
            if isinstance(payload, str):
                payload = json.loads(payload)

            log.info("Claimed job_id=%s | job_type=%s", job_id, job_type)

            handler = JOB_DISPATCHERS.get(job_type)
            if not handler:
                msg = f"Unsupported job_type: {job_type}"
                log.error(msg)
                report_job_failure(session, secret, job_id, msg)
                continue

            if not presigned_url:
                msg = f"Missing presigned R2 PUT URL for job {job_id}"
                log.error(msg)
                report_job_failure(session, secret, job_id, msg)
                continue

            output_file: Path | None = None
            try:
                job_t0 = time.monotonic()
                output_file = handler(session, job_id, payload)
                content_type = (
                    mimetypes.guess_type(output_file.name)[0]
                    or "application/octet-stream"
                )

                log.info("Uploading %s to R2...", output_file.name)
                file_size = upload_to_presigned_r2(
                    session, presigned_url, output_file, content_type
                )

                complete_meta = {
                    "job_id": job_id,
                    "job_type": job_type,
                    "r2_key": job.get("r2_key") or job.get("output_key"),
                    "content_type": content_type,
                    "file_size_bytes": file_size,
                    "inference_seconds": round(time.monotonic() - job_t0, 2),
                }
                ack = mark_job_complete(session, secret, job_id, complete_meta)
                jobs_completed += 1
                log.info(
                    "Completed job %s (%d bytes) -> FCM push triggered: %s",
                    job_id,
                    file_size,
                    ack,
                )
            except Exception as exc:
                log.exception("Job %s failed: %s", job_id, exc)
                report_job_failure(session, secret, job_id, str(exc))
            finally:
                # WHY: Removing finished artifacts from local disk keeps
                # Kaggle/Colab temporary storage from filling up across batches.
                if output_file and output_file.exists():
                    output_file.unlink(missing_ok=True)

    except KeyboardInterrupt:
        log.info("Interrupted by user. Exiting cleanly.")
    finally:
        session.close()
        log.info(
            "Session summary: %d job(s) completed in %.1fs.",
            jobs_completed,
            time.monotonic() - start_time,
        )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
