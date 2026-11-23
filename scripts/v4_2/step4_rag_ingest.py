import hashlib
import io
import json
import os
import tempfile
from pathlib import Path

import boto3
import requests
import tiktoken
from docling.datamodel.accelerator_options import AcceleratorDevice, AcceleratorOptions
from docling.datamodel.base_models import InputFormat
from docling.datamodel.pipeline_options import PdfPipelineOptions
from docling.document_converter import DocumentConverter, PdfFormatOption

R2_ACCOUNT_ID = os.environ["R2_ACCOUNT_ID"]
R2_ACCESS_KEY_ID = os.environ["R2_ACCESS_KEY_ID"]
R2_SECRET_ACCESS_KEY = os.environ["R2_SECRET_ACCESS_KEY"]
R2_BUCKET_NAME = os.environ["R2_BUCKET_NAME"]
R2_OBJECT_KEY = os.environ["R2_OBJECT_KEY"]

CF_ACCOUNT_ID = os.environ["CF_ACCOUNT_ID"]
CF_API_TOKEN = os.environ["CF_API_TOKEN"]
VECTORIZE_INDEX_NAME = os.environ["VECTORIZE_INDEX_NAME"]

EMBED_MODEL = "@cf/baai/bge-small-en-v1.5"
CHUNK_TOKENS = 450
OVERLAP_TOKENS = 50
EMBED_BATCH_SIZE = 50
UPSERT_BATCH_SIZE = 1000


def download_pdf_from_r2(object_key: str, dest_path: Path) -> None:
    s3 = boto3.client(
        service_name="s3",
        endpoint_url=f"https://{R2_ACCOUNT_ID}.r2.cloudflarestorage.com",
        aws_access_key_id=R2_ACCESS_KEY_ID,
        aws_secret_access_key=R2_SECRET_ACCESS_KEY,
        region_name="auto",
    )
    s3.download_file(R2_BUCKET_NAME, object_key, str(dest_path))


def parse_pdf_by_page(pdf_path: Path) -> dict[int, str]:
    pipeline_options = PdfPipelineOptions()
    pipeline_options.accelerator_options = AcceleratorOptions(num_threads=4, device=AcceleratorDevice.CPU)
    converter = DocumentConverter(format_options={InputFormat.PDF: PdfFormatOption(pipeline_options=pipeline_options)})
    doc = converter.convert(str(pdf_path)).document
    pages: dict[int, list[str]] = {}
    for item, _level in doc.iterate_items():
        prov = getattr(item, "prov", None)
        if not prov:
            continue
        page_no = prov[0].page_no
        text = item.export_to_markdown(doc=doc).strip() if hasattr(item, "export_to_markdown") else getattr(item, "text", "").strip()
        if text:
            pages.setdefault(page_no, []).append(text)
    return {page_no: "\n\n".join(blocks) for page_no, blocks in sorted(pages.items())}


def chunk_pages(pages: dict[int, str], filename: str, chunk_tokens: int = CHUNK_TOKENS, overlap_tokens: int = OVERLAP_TOKENS) -> list[dict]:
    encoder = tiktoken.get_encoding("cl100k_base")
    step = chunk_tokens - overlap_tokens
    chunks: list[dict] = []
    for page_no, page_text in pages.items():
        token_ids = encoder.encode(page_text)
        if not token_ids:
            continue
        for start in range(0, len(token_ids), step):
            window = token_ids[start : start + chunk_tokens]
            chunk_text = encoder.decode(window).strip()
            if not chunk_text:
                continue
            vector_id = hashlib.sha256(f"{filename}:p{page_no}:t{start}".encode("utf-8")).hexdigest()[:32]
            chunks.append({"id": vector_id, "text": chunk_text, "metadata": {"filename": filename, "page_no": page_no, "text": chunk_text}})
            if start + chunk_tokens >= len(token_ids):
                break
    return chunks


def embed_texts(texts: list[str]) -> list[list[float]]:
    url = f"https://api.cloudflare.com/client/v4/accounts/{CF_ACCOUNT_ID}/ai/run/{EMBED_MODEL}"
    headers = {"Authorization": f"Bearer {CF_API_TOKEN}", "Content-Type": "application/json"}
    embeddings: list[list[float]] = []
    for i in range(0, len(texts), EMBED_BATCH_SIZE):
        resp = requests.post(url, headers=headers, json={"text": texts[i : i + EMBED_BATCH_SIZE]}, timeout=60)
        resp.raise_for_status()
        embeddings.extend(resp.json()["result"]["data"])
    return embeddings


def upsert_to_vectorize(chunks: list[dict], vectors: list[list[float]]) -> None:
    url = f"https://api.cloudflare.com/client/v4/accounts/{CF_ACCOUNT_ID}/vectorize/v2/indexes/{VECTORIZE_INDEX_NAME}/upsert"
    headers = {"Authorization": f"Bearer {CF_API_TOKEN}"}
    for i in range(0, len(chunks), UPSERT_BATCH_SIZE):
        ndjson_lines = [json.dumps({"id": c["id"], "values": v, "metadata": c["metadata"]}) for c, v in zip(chunks[i : i + UPSERT_BATCH_SIZE], vectors[i : i + UPSERT_BATCH_SIZE])]
        ndjson_bytes = ("\n".join(ndjson_lines) + "\n").encode("utf-8")
        resp = requests.post(url, headers=headers, files={"vectors": ("vectors.ndjson", io.BytesIO(ndjson_bytes), "application/x-ndjson")}, timeout=120)
        resp.raise_for_status()


def main() -> None:
    filename = Path(R2_OBJECT_KEY).name
    with tempfile.TemporaryDirectory() as tmp_dir:
        local_pdf = Path(tmp_dir) / filename
        download_pdf_from_r2(R2_OBJECT_KEY, local_pdf)
        pages = parse_pdf_by_page(local_pdf)
    chunks = chunk_pages(pages, filename=filename)
    if not chunks:
        raise ValueError(f"No extractable text found in {filename}")
    vectors = embed_texts([c["text"] for c in chunks])
    upsert_to_vectorize(chunks, vectors)
    print(f"Upserted {len(chunks)} chunks from {filename} into {VECTORIZE_INDEX_NAME}.")


if __name__ == "__main__":
    main()
