# YUNA UI v5 — Batch 1-7 (modular)
Drop contents into app/src/main/assets/ (index.html + css/ js/ assets/). Classic scripts, no CDN, no build step.

## Pages
01-04 chat, composer, attachments, history | 05-08 live orb, live transcript, voice input, voice output (Yuna.tts)
09 float, 10 orb states, 11 vision, 12 camera | 13 settings, 14 appearance, 15 voice, 16 models/cloud
17 permissions, 18 security, 19 memory, 20 RAG (dummy) | 21 tools hub, 22 MCP, 23 API (dummy), 24 web research
25 projects, 26 code, 27 artifacts (empty-state dummies) | 28 diagnostics

## New localStorage keys (need approval)
yuna_v4_state (tool toggles), yuna_float_on. Removed: yuna_draft.

## NOT ported from Build 107 index.html
routeSingleCommand (local command router), stranger guard, WhatsApp intel UI, mood, notes sync, Rust/Puyal gateway fallback,
LOCAL brain logic. v5 sends everything to Cloud and executes returned actions[] through the allowlist.

## r2 changes
- Per-chat cloud sessionId (was one shared id for every chat); Live uses its own id
- Markdown renderer (code+Copy, tables, lists, links); regenerate replaces reply in place
- Home starter chips, Share chat; launch-tool "once" flag no longer consumed by Live
## r4 changes
- Per-message row: copy, 🔊 read (toggle, uses speakSmart*/stopTts, works even if persona=SILENT), 👍/👎 (field `f` on the message inside yuna_sessions), regenerate only on last reply, share. Row also appears on reopened chats.
- History: ✎ rename (UI.prompt), 📌 pin (field `pin`; pinned group on top, also in drawer, never dropped by the 60-chat cap)
- In-chat search (More menu → js/pages/find.js): highlights matches, ▲▼, Back closes
- No new localStorage key, no new bridge call. 👍👎 is local only (not sent to Cloud).
## Still missing (vs Claude-style chat)
streaming tokens, edit user message, sources chips, file/artifact outputs, image output, local command router
## r5 changes (see AUDIT.md)
Permission hub rewrite (labels, key passed to openPermissionSettings, 2s poll + onYunaAppResumed), entry banner, mic guard, reply pinned to its own chat, regenerate keeps attachments, ≥48px for switch/pill/copy/drawer rows, relabel on language change, time-based greeting, developer wording removed. No new bridge call / storage key.
