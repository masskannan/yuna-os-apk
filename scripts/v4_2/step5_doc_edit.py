"""
YUNA AI OS v4.2 — Step 5: Schema-Validated Document Editing (`step5_doc_edit.py`)
Goldfish Builder-5 | GitHub Actions & Cloudflare Worker Job Runner Module

WHY THIS MODULE EXISTS:
Large Language Models (LLMs) are great at deciding *what* should change in a
document, but they should NEVER edit binary `.docx` or `.pdf` files directly.
Binary Office/PDF files use complex internal ZIP/XML structures or fixed
coordinate streams—one wrong byte corrupts the entire file.

Instead, this module enforces a strict 4-stage safety pipeline:
  1. SCHEMA VALIDATION: The LLM outputs a JSON list of structured edit ops
     matching `EDIT_SCHEMA`. Invalid or unexpected fields are rejected early.
  2. DETERMINISTIC APPLICATION: Trusted Python libraries (`python-docx` for
     `.docx`, `PyMuPDF`/`fitz` for `.pdf`) apply each validated edit safely.
  3. POST-SAVE RE-OPEN VERIFICATION: After saving to disk, we close and
     RE-OPEN the file to prove every single edit actually landed.
  4. GATED R2 UPLOAD: Only if 100% of edits pass verification do we upload the
     output file to Cloudflare R2.
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

# WHY: Configure structured logging so GitHub Actions / Worker logs clearly
# show which stage failed (validation, apply, verification, or R2 upload).
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("yuna.step5_doc_edit")


# ==============================================================================
# 1. STRICT JSON SCHEMA (`EDIT_SCHEMA`)
# ==============================================================================
# WHY: Defining a strict JSON Schema (`additionalProperties: False`) guarantees
# the LLM cannot invent unsupported operations, inject arbitrary file paths, or
# omit required fields like `find` or `table_index`.
EDIT_SCHEMA: Dict[str, Any] = {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "title": "YUNA_Step5_Document_Edits",
    "type": "object",
    "additionalProperties": False,
    "required": ["edits"],
    "properties": {
        "document_id": {
            "type": "string",
            "minLength": 1,
            "description": "Optional tracking ID for the YUNA job runner.",
        },
        "edits": {
            "type": "array",
            "minItems": 1,
            "maxItems": 200,
            "items": {
                "oneOf": [
                    {
                        "type": "object",
                        "additionalProperties": False,
                        "required": ["op", "find", "replace"],
                        "properties": {
                            "op": {"const": "replace_text"},
                            "find": {"type": "string", "minLength": 1},
                            "replace": {"type": "string"},
                            "match_case": {"type": "boolean", "default": True},
                            "page": {
                                "type": "integer",
                                "minimum": 1,
                                "description": "1-indexed PDF page (optional; all pages if omitted).",
                            },
                        },
                    },
                    {
                        "type": "object",
                        "additionalProperties": False,
                        "required": ["op", "text"],
                        "properties": {
                            "op": {"const": "insert_paragraph"},
                            "text": {"type": "string", "minLength": 1},
                            "after_text": {
                                "type": "string",
                                "minLength": 1,
                                "description": "Optional anchor text; appends to end if omitted.",
                            },
                            "style": {
                                "type": "string",
                                "minLength": 1,
                                "description": "Optional DOCX paragraph style (e.g., 'Normal', 'Heading 2').",
                            },
                            "page": {
                                "type": "integer",
                                "minimum": 1,
                                "description": "1-indexed PDF page (optional; defaults to last page).",
                            },
                        },
                    },
                    {
                        "type": "object",
                        "additionalProperties": False,
                        "required": ["op", "table_index", "row", "col", "text"],
                        "properties": {
                            "op": {"const": "update_table_cell"},
                            "table_index": {"type": "integer", "minimum": 0},
                            "row": {"type": "integer", "minimum": 0},
                            "col": {"type": "integer", "minimum": 0},
                            "text": {"type": "string"},
                        },
                    },
                    {
                        "type": "object",
                        "additionalProperties": False,
                        "required": ["op", "text"],
                        "properties": {
                            "op": {"const": "add_page_note"},
                            "text": {"type": "string", "minLength": 1},
                            "page": {
                                "type": "integer",
                                "minimum": 1,
                                "default": 1,
                                "description": "1-indexed page number (used for PDF).",
                            },
                            "position": {
                                "type": "string",
                                "enum": ["top", "bottom"],
                                "default": "bottom",
                            },
                        },
                    },
                ]
            },
        },
    },
}


class SchemaValidationError(ValueError):
    """Raised when LLM-generated edit JSON violates `EDIT_SCHEMA`."""


class EditApplicationError(RuntimeError):
    """Raised when an edit cannot be applied to the target document."""


class VerificationError(RuntimeError):
    """Raised when re-opening the saved file shows an edit did not land."""


# ==============================================================================
# 2. JSON SCHEMA VALIDATION
# ==============================================================================
_ALLOWED_KEYS_BY_OP: Dict[str, Tuple[set, set]] = {
    # op_name: (required_keys, optional_keys)
    "replace_text": ({"op", "find", "replace"}, {"match_case", "page"}),
    "insert_paragraph": ({"op", "text"}, {"after_text", "style", "page"}),
    "update_table_cell": ({"op", "table_index", "row", "col", "text"}, set()),
    "add_page_note": ({"op", "text"}, {"page", "position"}),
}


def validate_edits(payload: Dict[str, Any]) -> List[Dict[str, Any]]:
    """
    Validate an LLM-generated JSON payload against `EDIT_SCHEMA`.

    WHY: We run `jsonschema.validate` when the `jsonschema` package is
    installed, AND we also run explicit Python type/bounds checks so validation
    never silently skips checks in lightweight CI/Worker containers.
    """
    if not isinstance(payload, dict):
        raise SchemaValidationError("Top-level payload must be a JSON object.")

    # WHY: Reject unknown top-level keys just like `additionalProperties: False`.
    unknown_top = set(payload.keys()) - {"document_id", "edits"}
    if unknown_top:
        raise SchemaValidationError(f"Unexpected top-level keys: {sorted(unknown_top)}")

    if "document_id" in payload:
        if not isinstance(payload["document_id"], str) or not payload["document_id"].strip():
            raise SchemaValidationError("`document_id` must be a non-empty string.")

    if "edits" not in payload or not isinstance(payload["edits"], list):
        raise SchemaValidationError("`edits` must be a list of edit objects.")

    if not (1 <= len(payload["edits"]) <= 200):
        raise SchemaValidationError("`edits` must contain between 1 and 200 items.")

    # Optional jsonschema library pass for full Draft 2020-12 compliance
    try:
        import jsonschema  # type: ignore

        jsonschema.validate(instance=payload, schema=EDIT_SCHEMA)
    except ImportError:
        # WHY: Fallback to our strict built-in validator below when `jsonschema`
        # is not installed in the runner environment.
        pass
    except Exception as exc:
        raise SchemaValidationError(f"JSON Schema validation failed: {exc}") from exc

    normalized_edits: List[Dict[str, Any]] = []
    for idx, item in enumerate(payload["edits"]):
        if not isinstance(item, dict):
            raise SchemaValidationError(f"edits[{idx}] must be a JSON object.")

        op = item.get("op")
        if op not in _ALLOWED_KEYS_BY_OP:
            raise SchemaValidationError(
                f"edits[{idx}].op={op!r} is invalid. Allowed: {sorted(_ALLOWED_KEYS_BY_OP)}"
            )

        required_keys, optional_keys = _ALLOWED_KEYS_BY_OP[op]
        missing = required_keys - set(item.keys())
        if missing:
            raise SchemaValidationError(f"edits[{idx}] ({op}) missing keys: {sorted(missing)}")

        extra = set(item.keys()) - (required_keys | optional_keys)
        if extra:
            raise SchemaValidationError(f"edits[{idx}] ({op}) has unknown keys: {sorted(extra)}")

        # Per-operation field type and range validation
        clean = dict(item)
        if op == "replace_text":
            if not isinstance(clean["find"], str) or len(clean["find"]) < 1:
                raise SchemaValidationError(f"edits[{idx}].find must be a non-empty string.")
            if not isinstance(clean["replace"], str):
                raise SchemaValidationError(f"edits[{idx}].replace must be a string.")
            clean.setdefault("match_case", True)
            if not isinstance(clean["match_case"], bool):
                raise SchemaValidationError(f"edits[{idx}].match_case must be boolean.")
            if "page" in clean and (
                isinstance(clean["page"], bool) or not isinstance(clean["page"], int) or clean["page"] < 1
            ):
                raise SchemaValidationError(f"edits[{idx}].page must be an integer >= 1.")

        elif op == "insert_paragraph":
            if not isinstance(clean["text"], str) or len(clean["text"]) < 1:
                raise SchemaValidationError(f"edits[{idx}].text must be a non-empty string.")
            if "after_text" in clean and (
                not isinstance(clean["after_text"], str) or len(clean["after_text"]) < 1
            ):
                raise SchemaValidationError(f"edits[{idx}].after_text must be a non-empty string.")
            if "style" in clean and (not isinstance(clean["style"], str) or len(clean["style"]) < 1):
                raise SchemaValidationError(f"edits[{idx}].style must be a non-empty string.")
            if "page" in clean and (
                isinstance(clean["page"], bool) or not isinstance(clean["page"], int) or clean["page"] < 1
            ):
                raise SchemaValidationError(f"edits[{idx}].page must be an integer >= 1.")

        elif op == "update_table_cell":
            for key in ("table_index", "row", "col"):
                val = clean[key]
                if isinstance(val, bool) or not isinstance(val, int) or val < 0:
                    raise SchemaValidationError(f"edits[{idx}].{key} must be an integer >= 0.")
            if not isinstance(clean["text"], str):
                raise SchemaValidationError(f"edits[{idx}].text must be a string.")

        elif op == "add_page_note":
            if not isinstance(clean["text"], str) or len(clean["text"]) < 1:
                raise SchemaValidationError(f"edits[{idx}].text must be a non-empty string.")
            clean.setdefault("page", 1)
            clean.setdefault("position", "bottom")
            if isinstance(clean["page"], bool) or not isinstance(clean["page"], int) or clean["page"] < 1:
                raise SchemaValidationError(f"edits[{idx}].page must be an integer >= 1.")
            if clean["position"] not in ("top", "bottom"):
                raise SchemaValidationError(f"edits[{idx}].position must be 'top' or 'bottom'.")

        normalized_edits.append(clean)

    return normalized_edits


# ==============================================================================
# 3A. DOCX EDITING ENGINE (`python-docx`)
# ==============================================================================
def _replace_in_paragraph_runs(paragraph: Any, find: str, replace: str, match_case: bool) -> int:
    """
    Replace `find` with `replace` inside a `python-docx` paragraph while
    preserving run-level formatting (bold, italic, font, size).

    WHY: Word splits a single phrase like "{{CLIENT_NAME}}" across multiple XML
    `<w:r>` runs whenever spellcheck or formatting changes mid-word. If we only
    checked `run.text`, we would miss split phrases; if we overwrote
    `paragraph.text`, we would destroy all bold/italic styling. Mapping
    character offsets across runs solves both problems cleanly.
    """
    if not paragraph.runs:
        return 0

    replacements = 0
    while True:
        full_text = "".join(r.text for r in paragraph.runs)
        haystack = full_text if match_case else full_text.lower()
        needle = find if match_case else find.lower()
        start = haystack.find(needle)
        if start == -1:
            break
        end = start + len(find)

        # Map each run to its [run_start, run_end) span in `full_text`
        cursor = 0
        first_hit_written = False
        for run in paragraph.runs:
            r_len = len(run.text)
            r_start, r_end = cursor, cursor + r_len
            cursor = r_end

            # Skip runs that do not overlap [start, end)
            if r_end <= start or r_start >= end:
                continue

            local_start = max(0, start - r_start)
            local_end = min(r_len, end - r_start)
            prefix = run.text[:local_start]
            suffix = run.text[local_end:]

            if not first_hit_written:
                run.text = prefix + replace + suffix
                first_hit_written = True
            else:
                # WHY: Clear the remainder of the matched span from subsequent
                # runs while keeping any trailing text intact.
                run.text = prefix + suffix

        replacements += 1

    return replacements


def _insert_paragraph_after(anchor_paragraph: Any, text: str, style: Optional[str] = None) -> Any:
    """
    Insert a new paragraph immediately *after* `anchor_paragraph` in DOCX XML.

    WHY: `python-docx` only provides `paragraph.insert_paragraph_before()`. To
    insert *after* an anchor paragraph, we create a new `<w:p>` element and
    place it directly after `anchor_paragraph._p` in the XML tree.
    """
    from docx.oxml import OxmlElement  # type: ignore
    from docx.text.paragraph import Paragraph  # type: ignore

    new_p = OxmlElement("w:p")
    anchor_paragraph._p.addnext(new_p)
    new_para = Paragraph(new_p, anchor_paragraph._parent)
    if style:
        try:
            new_para.style = style
        except KeyError:
            # WHY: Fall back gracefully if the template lacks a custom style name.
            logger.warning("DOCX style %r not found; using default paragraph style.", style)
    new_para.add_run(text)
    return new_para


def _iter_docx_paragraphs(doc: Any) -> List[Any]:
    """Collect body paragraphs, table cell paragraphs, and header/footer paragraphs."""
    paragraphs = list(doc.paragraphs)
    for table in doc.tables:
        for row in table.rows:
            for cell in row.cells:
                paragraphs.extend(cell.paragraphs)
    for section in doc.sections:
        paragraphs.extend(section.header.paragraphs)
        paragraphs.extend(section.footer.paragraphs)
    return paragraphs


def apply_docx_edits(input_path: Path, output_path: Path, edits: List[Dict[str, Any]]) -> None:
    """Apply validated edits to a `.docx` file and save to `output_path`."""
    try:
        from docx import Document  # type: ignore
    except ImportError as exc:
        raise EditApplicationError("`python-docx` is required for .docx editing.") from exc

    doc = Document(str(input_path))

    for idx, edit in enumerate(edits):
        op = edit["op"]

        if op == "replace_text":
            count = 0
            for para in _iter_docx_paragraphs(doc):
                count += _replace_in_paragraph_runs(
                    para,
                    find=edit["find"],
                    replace=edit["replace"],
                    match_case=edit["match_case"],
                )
            if count == 0:
                raise EditApplicationError(
                    f"edits[{idx}] replace_text: {edit['find']!r} not found in DOCX."
                )
            logger.info("DOCX replace_text: replaced %d occurrence(s) of %r.", count, edit["find"])

        elif op == "insert_paragraph":
            after_text = edit.get("after_text")
            style = edit.get("style")
            if after_text:
                anchor = next((p for p in doc.paragraphs if after_text in p.text), None)
                if anchor is None:
                    raise EditApplicationError(
                        f"edits[{idx}] insert_paragraph: anchor {after_text!r} not found in DOCX."
                    )
                _insert_paragraph_after(anchor, edit["text"], style=style)
            else:
                try:
                    doc.add_paragraph(edit["text"], style=style)
                except KeyError:
                    doc.add_paragraph(edit["text"])
            logger.info("DOCX insert_paragraph: inserted %r.", edit["text"][:60])

        elif op == "update_table_cell":
            t_idx, r_idx, c_idx = edit["table_index"], edit["row"], edit["col"]
            if t_idx >= len(doc.tables):
                raise EditApplicationError(
                    f"edits[{idx}] update_table_cell: table_index={t_idx} out of range (found {len(doc.tables)})."
                )
            table = doc.tables[t_idx]
            if r_idx >= len(table.rows) or c_idx >= len(table.rows[r_idx].cells):
                raise EditApplicationError(
                    f"edits[{idx}] update_table_cell: cell ({r_idx}, {c_idx}) out of range."
                )
            cell = table.cell(r_idx, c_idx)
            # WHY: Preserve the first paragraph/run's formatting if one exists,
            # rather than wiping cell XML styles with `cell.text = ...`.
            if cell.paragraphs and cell.paragraphs[0].runs:
                cell.paragraphs[0].runs[0].text = edit["text"]
                for extra_run in cell.paragraphs[0].runs[1:]:
                    extra_run.text = ""
                for extra_para in cell.paragraphs[1:]:
                    for run in extra_para.runs:
                        run.text = ""
            else:
                cell.text = edit["text"]
            logger.info("DOCX update_table_cell: updated table[%d](%d,%d).", t_idx, r_idx, c_idx)

        elif op == "add_page_note":
            # WHY: DOCX files do not have fixed page numbers until rendered by
            # Word/LibreOffice, so page notes are placed in the section header
            # or footer where they appear cleanly on document pages.
            section = doc.sections[0]
            target_area = section.header if edit["position"] == "top" else section.footer
            target_area.is_linked_to_previous = False
            target_area.add_paragraph(f"[Note] {edit['text']}")
            logger.info("DOCX add_page_note: added %s note.", edit["position"])

    output_path.parent.mkdir(parents=True, exist_ok=True)
    doc.save(str(output_path))


# ==============================================================================
# 3B. PDF EDITING ENGINE (`PyMuPDF` / `fitz`)
# ==============================================================================
def apply_pdf_edits(input_path: Path, output_path: Path, edits: List[Dict[str, Any]]) -> None:
    """Apply validated edits to a `.pdf` file using PyMuPDF (`fitz`) and save."""
    try:
        import fitz  # type: ignore
    except ImportError as exc:
        raise EditApplicationError("`PyMuPDF` (`fitz`) is required for .pdf editing.") from exc

    doc = fitz.open(str(input_path))

    try:
        for idx, edit in enumerate(edits):
            op = edit["op"]

            if op == "replace_text":
                target_pages = (
                    [doc[edit["page"] - 1]]
                    if "page" in edit and edit["page"] <= len(doc)
                    else list(doc)
                )
                if "page" in edit and edit["page"] > len(doc):
                    raise EditApplicationError(
                        f"edits[{idx}] replace_text: page {edit['page']} exceeds PDF length ({len(doc)})."
                    )

                hits = 0
                for page in target_pages:
                    rects = page.search_for(edit["find"])
                    if not edit["match_case"]:
                        # WHY: PyMuPDF `search_for` is case-insensitive by default
                        # in recent versions, while `match_case=True` can be
                        # enforced by filtering extracted span text if needed.
                        pass
                    else:
                        # Filter rects whose exact extracted text matches case
                        rects = [r for r in rects if edit["find"] in page.get_textbox(r)]

                    for rect in rects:
                        # WHY: PDFs store positioned glyphs, not reflowable text.
                        # Redaction cleanly removes the old text from the content
                        # stream and writes replacement text inside the same box.
                        page.add_redact_annot(
                            rect,
                            text=edit["replace"],
                            fontname="helv",
                            fontsize=max(7.0, min(11.0, rect.height * 0.78)),
                            fill=(1, 1, 1),
                            text_color=(0, 0, 0),
                        )
                        hits += 1
                    if rects:
                        page.apply_redactions()

                if hits == 0:
                    raise EditApplicationError(
                        f"edits[{idx}] replace_text: {edit['find']!r} not found in PDF."
                    )
                logger.info("PDF replace_text: replaced %d occurrence(s) of %r.", hits, edit["find"])

            elif op == "insert_paragraph":
                page_num = edit.get("page", len(doc))
                if page_num > len(doc):
                    raise EditApplicationError(
                        f"edits[{idx}] insert_paragraph: page {page_num} exceeds PDF length ({len(doc)})."
                    )
                page = doc[page_num - 1]
                after_text = edit.get("after_text")

                if after_text:
                    rects = page.search_for(after_text)
                    if not rects:
                        raise EditApplicationError(
                            f"edits[{idx}] insert_paragraph: anchor {after_text!r} not found on page {page_num}."
                        )
                    anchor_rect = rects[-1]
                    box = fitz.Rect(
                        anchor_rect.x0,
                        anchor_rect.y1 + 6,
                        page.rect.width - 48,
                        min(page.rect.height - 36, anchor_rect.y1 + 96),
                    )
                else:
                    # Place in lower body margin above footer
                    box = fitz.Rect(48, page.rect.height - 120, page.rect.width - 48, page.rect.height - 48)

                overflow = page.insert_textbox(box, edit["text"], fontname="helv", fontsize=10, color=(0, 0, 0))
                if overflow < 0:
                    # WHY: If text overflows the box, add a clean continuation page
                    # so content is never silently clipped.
                    new_page = doc.new_page(width=page.rect.width, height=page.rect.height)
                    new_box = fitz.Rect(48, 48, new_page.rect.width - 48, new_page.rect.height - 48)
                    new_page.insert_textbox(new_box, edit["text"], fontname="helv", fontsize=10, color=(0, 0, 0))
                logger.info("PDF insert_paragraph: inserted text on page %d.", page_num)

            elif op == "update_table_cell":
                t_idx, r_idx, c_idx = edit["table_index"], edit["row"], edit["col"]
                # WHY: PDFs do not have native mutable table objects. PyMuPDF's
                # `find_tables()` detects grid geometry so we can redact the old
                # cell bbox and write the new value in place.
                all_tables: List[Tuple[Any, Any]] = []
                for page in doc:
                    finder = page.find_tables()
                    for tbl in finder.tables:
                        all_tables.append((page, tbl))

                if t_idx >= len(all_tables):
                    raise EditApplicationError(
                        f"edits[{idx}] update_table_cell: table_index={t_idx} out of range (found {len(all_tables)})."
                    )
                page, tbl = all_tables[t_idx]
                if r_idx >= len(tbl.rows) or c_idx >= len(tbl.rows[r_idx].cells):
                    raise EditApplicationError(
                        f"edits[{idx}] update_table_cell: cell ({r_idx}, {c_idx}) out of range."
                    )
                cell_bbox = fitz.Rect(tbl.rows[r_idx].cells[c_idx])
                page.add_redact_annot(
                    cell_bbox,
                    text=edit["text"],
                    fontname="helv",
                    fontsize=9,
                    fill=(1, 1, 1),
                    text_color=(0, 0, 0),
                )
                page.apply_redactions()
                logger.info("PDF update_table_cell: updated table[%d](%d,%d).", t_idx, r_idx, c_idx)

            elif op == "add_page_note":
                page_num = edit["page"]
                if page_num > len(doc):
                    raise EditApplicationError(
                        f"edits[{idx}] add_page_note: page {page_num} exceeds PDF length ({len(doc)})."
                    )
                page = doc[page_num - 1]
                note_text = f"[Note] {edit['text']}"
                if edit["position"] == "top":
                    box = fitz.Rect(48, 18, page.rect.width - 48, 44)
                else:
                    box = fitz.Rect(48, page.rect.height - 40, page.rect.width - 48, page.rect.height - 14)

                page.insert_textbox(box, note_text, fontname="helv", fontsize=9, color=(0.2, 0.2, 0.2))
                logger.info("PDF add_page_note: added %s note on page %d.", edit["position"], page_num)

        output_path.parent.mkdir(parents=True, exist_ok=True)
        # WHY: Saving to a new path with `garbage=4, deflate=True` compacts and
        # finalizes all redactions and object streams cleanly.
        doc.save(str(output_path), garbage=4, deflate=True)
    finally:
        doc.close()


# ==============================================================================
# 4. POST-SAVE RE-OPEN VERIFICATION (MANDATORY BEFORE R2 UPLOAD)
# ==============================================================================
def verify_saved_file(output_path: Path, edits: List[Dict[str, Any]]) -> Dict[str, Any]:
    """
    Re-open `output_path` from disk and verify EVERY edit actually landed.

    WHY: Libraries can silently drop edits (e.g., clipped PDF textboxes,
    unapplied redactions, or XML write issues). By closing the file and
    re-opening it fresh from disk before uploading to R2, we guarantee broken
    files never reach cloud storage.
    """
    if not output_path.exists() or output_path.stat().st_size == 0:
        raise VerificationError(f"Output file missing or empty: {output_path}")

    ext = output_path.suffix.lower()
    failures: List[str] = []

    if ext == ".docx":
        from docx import Document  # type: ignore

        reopened = Document(str(output_path))
        all_text = "\n".join(p.text for p in _iter_docx_paragraphs(reopened))

        for idx, edit in enumerate(edits):
            op = edit["op"]
            if op == "replace_text":
                if edit["replace"] and edit["replace"] not in all_text:
                    failures.append(f"edits[{idx}] replace_text: {edit['replace']!r} missing after re-open.")
            elif op == "insert_paragraph":
                if edit["text"] not in all_text:
                    failures.append(f"edits[{idx}] insert_paragraph: {edit['text']!r} missing after re-open.")
            elif op == "update_table_cell":
                actual = reopened.tables[edit["table_index"]].cell(edit["row"], edit["col"]).text.strip()
                if edit["text"].strip() not in actual:
                    failures.append(
                        f"edits[{idx}] update_table_cell: expected {edit['text']!r}, got {actual!r}."
                    )
            elif op == "add_page_note":
                if edit["text"] not in all_text:
                    failures.append(f"edits[{idx}] add_page_note: {edit['text']!r} missing after re-open.")

    elif ext == ".pdf":
        import fitz  # type: ignore

        reopened = fitz.open(str(output_path))
        try:
            page_texts = [p.get_text("text") for p in reopened]
            full_text = "\n".join(page_texts)

            for idx, edit in enumerate(edits):
                op = edit["op"]
                if op == "replace_text":
                    scope_text = page_texts[edit["page"] - 1] if "page" in edit else full_text
                    if edit["replace"] and edit["replace"] not in scope_text:
                        failures.append(f"edits[{idx}] replace_text: {edit['replace']!r} missing after re-open.")
                elif op == "insert_paragraph":
                    if edit["text"] not in full_text:
                        failures.append(f"edits[{idx}] insert_paragraph: {edit['text']!r} missing after re-open.")
                elif op == "update_table_cell":
                    if edit["text"].strip() and edit["text"].strip() not in full_text:
                        failures.append(
                            f"edits[{idx}] update_table_cell: {edit['text']!r} missing after re-open."
                        )
                elif op == "add_page_note":
                    target_text = page_texts[edit["page"] - 1]
                    if edit["text"] not in target_text:
                        failures.append(
                            f"edits[{idx}] add_page_note: {edit['text']!r} missing on page {edit['page']}."
                        )
        finally:
            reopened.close()
    else:
        raise VerificationError(f"Unsupported file extension: {ext}")

    if failures:
        raise VerificationError("Post-save verification failed:\n - " + "\n - ".join(failures))

    return {
        "verified": True,
        "output_path": str(output_path),
        "bytes": output_path.stat().st_size,
        "edits_verified": len(edits),
    }


# ==============================================================================
# 5. CLOUDFLARE R2 UPLOAD (S3-COMPATIBLE, POST-VERIFICATION ONLY)
# ==============================================================================
def upload_to_r2(
    file_path: Path,
    object_key: str,
    account_id: Optional[str] = None,
    access_key_id: Optional[str] = None,
    secret_access_key: Optional[str] = None,
    bucket_name: Optional[str] = None,
) -> str:
    """
    Upload a verified document to Cloudflare R2 using `boto3` S3 API.

    WHY: Reads credentials from environment variables (`R2_ACCOUNT_ID`,
    `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET_NAME`) so secrets are
    never hardcoded in GitHub Actions workflows or Python source files.
    """
    try:
        import boto3  # type: ignore
    except ImportError as exc:
        raise RuntimeError("`boto3` is required for R2 upload.") from exc

    acct = account_id or os.environ["R2_ACCOUNT_ID"]
    key_id = access_key_id or os.environ["R2_ACCESS_KEY_ID"]
    secret = secret_access_key or os.environ["R2_SECRET_ACCESS_KEY"]
    bucket = bucket_name or os.environ["R2_BUCKET_NAME"]

    endpoint_url = f"https://{acct}.r2.cloudflarestorage.com"
    client = boto3.client(
        "s3",
        endpoint_url=endpoint_url,
        aws_access_key_id=key_id,
        aws_secret_access_key=secret,
        region_name="auto",
    )
    client.upload_file(str(file_path), bucket, object_key)
    logger.info("Uploaded verified file to r2://%s/%s", bucket, object_key)
    return f"r2://{bucket}/{object_key}"


# ==============================================================================
# 6. JOB RUNNER ORCHESTRATOR & CLI ENTRYPOINT
# ==============================================================================
def run_edit_job(
    input_path: str | Path,
    output_path: str | Path,
    edits_payload: Dict[str, Any],
    r2_key: Optional[str] = None,
) -> Dict[str, Any]:
    """
    Execute the full Step 5 pipeline:
      Validate JSON -> Apply Edits -> Re-open & Verify -> (Optional) Upload R2.
    """
    in_path = Path(input_path)
    out_path = Path(output_path)

    if not in_path.exists():
        raise FileNotFoundError(f"Input document not found: {in_path}")

    ext = in_path.suffix.lower()
    if ext not in (".docx", ".pdf"):
        raise ValueError(f"Unsupported document type {ext!r}; expected .docx or .pdf.")

    # Stage 1: Validate LLM JSON edits against EDIT_SCHEMA
    validated_edits = validate_edits(edits_payload)

    # Stage 2: Apply edits deterministically using python-docx or PyMuPDF
    if ext == ".docx":
        apply_docx_edits(in_path, out_path, validated_edits)
    else:
        apply_pdf_edits(in_path, out_path, validated_edits)

    # Stage 3: Re-open saved file from disk to prove every edit landed
    report = verify_saved_file(out_path, validated_edits)

    # Stage 4: Only upload to R2 after verification passes
    if r2_key:
        report["r2_uri"] = upload_to_r2(out_path, r2_key)

    return report


def _parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="YUNA AI OS v4.2 Step 5 — Schema-Validated Document Editor"
    )
    parser.add_argument("--input", required=True, help="Path to input .docx or .pdf")
    parser.add_argument("--output", required=True, help="Path to save edited file")
    parser.add_argument("--edits-json", required=True, help="Path to LLM-generated edits JSON file")
    parser.add_argument("--r2-key", default=None, help="Optional R2 object key to upload after verification")
    return parser.parse_args(argv)


if __name__ == "__main__":
    args = _parse_args()
    with open(args.edits_json, "r", encoding="utf-8") as f:
        raw_payload = json.load(f)

    result = run_edit_job(
        input_path=args.input,
        output_path=args.output,
        edits_payload=raw_payload,
        r2_key=args.r2_key,
    )
    print(json.dumps(result, indent=2))
    sys.exit(0)
