export default {
  async fetch(request, env, ctx) {
    const cors = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type, Authorization",
      "Content-Type": "application/json; charset=utf-8"
    };
    if (request.method === "OPTIONS") return new Response(null, { headers: cors });

    const u0 = new URL(request.url);
    if (u0.pathname === "/health" && request.method === "GET") {
      return new Response(JSON.stringify({
        ok: true,
        worker: "puyal-v42-1k-audited",
        bindings: {
          groq: getGroqKeys(env).length > 0,
          nvidia: Boolean(env.NVIDIA_API_KEY),
          ai: Boolean(env.AI),
          db: Boolean(env.DB),
          vectorize: Boolean(env.VECTORIZE)
        },
        ts: Date.now()
      }), { headers: cors });
    }

    if (u0.pathname === "/backup/export" && request.method === "GET") {
      if (!env.DB) return new Response(JSON.stringify({ ok: false, error: "DB binding missing" }), { headers: cors });
      const out = { ok: true, exported_at: new Date().toISOString(), tables: {} };
      for (const tbl of ["yuna_memory", "yuna_notes", "loads", "rag_docs", "yuna_pending_intents"]) {
        try {
          const r = await env.DB.prepare(`SELECT * FROM ${tbl} ORDER BY 1 DESC LIMIT 500`).all();
          out.tables[tbl] = r?.results || [];
        } catch (te) {
          out.tables[tbl] = { note: "table_not_created_yet" };
        }
      }
      return new Response(JSON.stringify(out, null, 2), { headers: cors });
    }

    if (u0.pathname === "/egm/review" && request.method === "POST") {
      try {
        const eb = await request.json();
        const mode = String(eb.mode || "code").slice(0, 20);
        const title = String(eb.title || "Untitled Artifact").slice(0, 120);
        const spec = String(eb.spec || "").slice(0, 4000);
        const content = String(eb.content || eb.code || "").slice(0, 12000);
        if (!content && !spec) {
          return new Response(JSON.stringify({ ok: false, error: "spec or content required" }), { status: 400, headers: cors });
        }
        const goldSys = [
          "You are a ZERO-MEMORY Goldfish Reviewer (based on Google's Elephant-Goldfish Model).",
          "You have ZERO prior conversation history. Judge ONLY the provided specification and code/document.",
          "Audit ruthlessly across 3 lenses: (1) Logic & Architecture blind spots, (2) Security/Safety/Data corruption risks, (3) Missing edge cases & Spec-vs-Code drift.",
          "Never sugarcoat. Do not invent facts outside the provided text.",
          "Return strict JSON only: {\"verdict\":\"APPROVE|REVISE|BLOCK\",\"summary\":\"1-2 crisp sentences in Tamil\",\"critical_risks\":[\"...\"],\"missing_assumptions\":[\"...\"],\"action_items\":[\"...\"]}"
        ].join(" ");
        const goldUser = `Mode: ${mode}\nTitle: ${title}\n${spec ? "=== DESIGN SPEC ===\n" + spec + "\n" : ""}=== ARTIFACT CONTENT ===\n${content}`;
        const msgs = [{ role: "system", content: goldSys }, { role: "user", content: goldUser }];
        const errLog = [];
        let raw = "";
        if (env.NVIDIA_API_KEY) raw = await askNvidiaNim(env.NVIDIA_API_KEY, msgs, 1000, errLog);
        if (!raw && getGroqKeys(env).length) raw = await askGroq(env, msgs, errLog);
        if (!raw && env.AI) {
          try {
            const cf = await env.AI.run("@cf/meta/llama-3.3-70b-instruct-fp8-fast", { messages: msgs, max_tokens: 900 });
            raw = (typeof cf?.response === "string") ? cf.response : "";
          } catch (ce) { errLog.push(`cf:${ce?.message || "err"}`); }
        }
        if (!raw) return new Response(JSON.stringify({ ok: false, verdict: "BLOCK", error: "No AI provider available", debug_errors: errLog }), { headers: cors });
        const cl = raw.replace(/^```json\s*/i, "").replace(/```$/i, "").trim();
        const js = (cl.indexOf("{") !== -1 && cl.lastIndexOf("}") > cl.indexOf("{")) ? cl.slice(cl.indexOf("{"), cl.lastIndexOf("}") + 1) : cl;
        let parsed = {};
        try { parsed = JSON.parse(js); } catch (_) { parsed = { verdict: "REVISE", summary: cl.slice(0, 300), critical_risks: [], missing_assumptions: [], action_items: [] }; }
        return new Response(JSON.stringify({ ok: true, mode, title, ...parsed }), { headers: cors });
      } catch (e) {
        return new Response(JSON.stringify({ ok: false, error: e.message }), { headers: cors });
      }
    }

    if (u0.pathname === "/vision" && request.method === "POST") {
      try {
        const body = await request.json();
        const prompt = String(body.prompt || "இந்தப் படம் / திரையைத் தமிழில் சுருக்கமாக விளக்குக.").slice(0, 600);
        const imgs = Array.isArray(body.images) ? body.images.slice(0, 5) : (body.imageBase64 ? [{ base64: body.imageBase64, mimeType: body.mimeType || "image/jpeg" }] : []);
        if (!imgs.length) return new Response(JSON.stringify({ ok: false, reply: "படம் இல்லை." }), { status: 400, headers: cors });
        if (!getGroqKeys(env).length && !env.NVIDIA_API_KEY && !env.AI) return new Response(JSON.stringify({ ok: false, reply: "Vision AI key இல்லை." }), { status: 500, headers: cors });

        const content = [{ type: "text", text: prompt + " (தமிழில் சுருக்கமாக, தெளிவாகப் பதிலளி.)" }];
        for (const im of imgs) {
          const b64 = String(im.base64 || "").replace(/^data:[^;]+;base64,/, "");
          if (b64) content.push({ type: "image_url", image_url: { url: `data:${im.mimeType || "image/jpeg"};base64,${b64}` } });
        }
        let vReply = "";
        for (const k of getGroqKeys(env)) {
          try {
            const vr = await fetch("https://api.groq.com/openai/v1/chat/completions", {
              method: "POST",
              headers: { "Authorization": "Bearer " + k, "Content-Type": "application/json" },
              body: JSON.stringify({ model: "meta-llama/llama-4-scout-17b-16e-instruct", temperature: 0.2, max_tokens: 700, messages: [{ role: "user", content }] })
            });
            if (vr.status === 429 || vr.status >= 500) continue;
            const vd = await vr.json();
            vReply = vd?.choices?.[0]?.message?.content || "";
            if (vReply) break;
          } catch (_) {}
        }
        if (!vReply && env.NVIDIA_API_KEY) {
          vReply = await askNvidiaVision(env.NVIDIA_API_KEY, content);
        }
        if (!vReply && env.AI) {
          try {
            const firstB64 = String(imgs[0]?.base64 || "").replace(/^data:[^;]+;base64,/, "");
            if (firstB64.length < 160000) {
              const bin = Uint8Array.from(atob(firstB64), c => c.charCodeAt(0));
              const cfVis = await env.AI.run("@cf/meta/llama-3.2-11b-vision-instruct", { prompt: prompt + " (தமிழில் சுருக்கமாகப் பதிலளி.)", image: Array.from(bin) });
              vReply = cfVis?.description || cfVis?.response || "";
            }
          } catch (_) {}
        }
        vReply = vReply || "படத்தைப் படிக்க முடியவில்லை.";
        return new Response(JSON.stringify({ ok: true, reply: vReply }), { headers: cors });
      } catch (ve) {
        return new Response(JSON.stringify({ ok: false, reply: "Vision பிழை: " + ve.message }), { status: 200, headers: cors });
      }
    }

    
    if (u0.pathname === "/rag/upsert" && request.method === "POST") {
      try {
        const rb = await request.json();
        const docId = String(rb.id || crypto.randomUUID());
        const text = String(rb.text || "").trim().slice(0, 2000);
        if (!text) return new Response(JSON.stringify({ ok: false, error: "empty text" }), { status: 400, headers: cors });
        if (env.DB) {
          await env.DB.prepare("CREATE TABLE IF NOT EXISTS rag_docs (id TEXT PRIMARY KEY, text TEXT, tag TEXT, ts INTEGER)").run();
          await env.DB.prepare("INSERT OR REPLACE INTO rag_docs (id, text, tag, ts) VALUES (?, ?, ?, ?)")
            .bind(docId, text, String(rb.tag || "general"), Date.now()).run();
        }
        if (env.VECTORIZE && env.AI) {
          const emb = await env.AI.run("@cf/baai/bge-m3", { text: [text] });
          const vec = emb?.data?.[0];
          if (vec) await env.VECTORIZE.upsert([{ id: docId, values: vec, metadata: { text, tag: String(rb.tag || "general") } }]);
        }
        return new Response(JSON.stringify({ ok: true, id: docId }), { headers: cors });
      } catch (re) {
        return new Response(JSON.stringify({ ok: false, error: re.message }), { headers: cors });
      }
    }

    if (u0.pathname === "/rag/query" && request.method === "POST") {
      try {
        const qb = await request.json();
        const qText = String(qb.query || "").trim().slice(0, 500);
        if (!qText) return new Response(JSON.stringify({ ok: false, matches: [] }), { headers: cors });
        if (env.VECTORIZE && env.AI) {
          const emb = await env.AI.run("@cf/baai/bge-m3", { text: [qText] });
          const vec = emb?.data?.[0];
          if (vec) {
            const vRes = await env.VECTORIZE.query(vec, { topK: 5, returnMetadata: "all" });
            return new Response(JSON.stringify({ ok: true, mode: "vectorize", matches: vRes?.matches || [] }), { headers: cors });
          }
        }
        if (env.DB) {
          await env.DB.prepare("CREATE TABLE IF NOT EXISTS rag_docs (id TEXT PRIMARY KEY, text TEXT, tag TEXT, ts INTEGER)").run();
          const dRes = await env.DB.prepare("SELECT id, text, tag, ts FROM rag_docs WHERE text LIKE ? ORDER BY ts DESC LIMIT 5").bind(`%${qText}%`).all();
          return new Response(JSON.stringify({ ok: true, mode: "d1-fallback", matches: dRes?.results || [] }), { headers: cors });
        }
        return new Response(JSON.stringify({ ok: true, mode: "none", matches: [] }), { headers: cors });
      } catch (qe) {
        return new Response(JSON.stringify({ ok: false, error: qe.message }), { headers: cors });
      }
    }

    if (u0.pathname === "/loads") {
      if (!env.DB) return new Response(JSON.stringify({ ok: false, loads: [], error: "D1 DB இல்லை" }), { headers: cors });
      try {
        await env.DB.prepare("CREATE TABLE IF NOT EXISTS loads (id INTEGER PRIMARY KEY AUTOINCREMENT, route TEXT, vehicle TEXT, rate TEXT, status TEXT DEFAULT 'OPEN', ts INTEGER)").run();
        if (request.method === "GET") {
          const q = (u0.searchParams.get("q") || "").trim();
          const res = q
            ? await env.DB.prepare("SELECT * FROM loads WHERE route LIKE ? OR vehicle LIKE ? ORDER BY id DESC LIMIT 20").bind(`%${q}%`, `%${q}%`).all()
            : await env.DB.prepare("SELECT * FROM loads ORDER BY id DESC LIMIT 20").all();
          return new Response(JSON.stringify({ ok: true, loads: res?.results || [] }), { headers: cors });
        }
        if (request.method === "POST") {
          const lb = await request.json();
          await env.DB.prepare("INSERT INTO loads (route, vehicle, rate, status, ts) VALUES (?, ?, ?, ?, ?)")
            .bind(String(lb.route || ""), String(lb.vehicle || ""), String(lb.rate || ""), String(lb.status || "OPEN"), Date.now()).run();
          return new Response(JSON.stringify({ ok: true }), { headers: cors });
        }
      } catch (le) {
        return new Response(JSON.stringify({ ok: false, error: le.message }), { headers: cors });
      }
    }


    const u = new URL(request.url);
    const sidParam = (u.searchParams.get("sid") || "default").replace(/[^a-zA-Z0-9_-]/g, "").slice(0, 40) || "default";

    try {
      // Cloud Voice Output: Edge TTS WebSocket (Tamil Venba/Pallavi/etc.) + Workers AI English Fallback (/tts)
      if (u.pathname === "/tts" && request.method === "POST") {
        const { text = "", voice = "" } = await request.json();
        const clean = formatTtsTamil(String(text)).replace(/[*_`~#⚡•●▶⏸➤]/g, " ").replace(/\s+/g, " ").trim().slice(0, 500);
        if (!clean) return new Response(JSON.stringify({ ok: false }), { status: 400, headers: cors });

        const hasTamil = /[\u0B80-\u0BFF]/.test(clean);
        const allowedVoices = new Set([
          "ta-SG-VenbaNeural", "ta-IN-PallaviNeural", "ta-IN-ValluvarNeural", "ta-SG-AnbuNeural",
          "ta-MY-KaniNeural", "ta-MY-SuryaNeural", "ta-LK-SaranyaNeural", "ta-LK-KumarNeural",
          "en-US-AvaMultilingualNeural", "en-IN-NeerjaNeural"
        ]);
        const targetVoice = allowedVoices.has(voice) ? voice : (hasTamil ? "ta-SG-VenbaNeural" : "en-IN-NeerjaNeural");

        try {
          const mp3Bytes = await synthEdgeTts(clean, targetVoice);
          if (mp3Bytes && mp3Bytes.byteLength > 200) {
            return new Response(mp3Bytes, {
              headers: { "Access-Control-Allow-Origin": "*", "Content-Type": "audio/mpeg" }
            });
          }
        } catch (_) {}

        if (!hasTamil && env.AI) {
          try {
            const audio = await env.AI.run("@cf/deepgram/aura-1", { text: clean, speaker: "luna" });
            return new Response(audio, {
              headers: { "Access-Control-Allow-Origin": "*", "Content-Type": "audio/mpeg" }
            });
          } catch (_) {}
        }
        return new Response(JSON.stringify({ ok: false }), { status: 503, headers: cors });
      }

      // Cloud Voice Input: Groq Whisper-large-v3 (/stt)
      if (u.pathname === "/stt" && request.method === "POST") {
        const sttKeys = getGroqKeys(env);
        const form = await request.formData();
        const file = form.get("file");
        if (!file) return new Response(JSON.stringify({ ok: false, text: "", error: "No audio file" }), { status: 400, headers: cors });

        const audioBuf = (file && typeof file.arrayBuffer === "function") ? await file.arrayBuffer() : await new Response(file).arrayBuffer();
        const audioBytes = new Uint8Array(audioBuf);
        const origName = String(file.name || "yuna_mic.wav").toLowerCase();
        const isMp3 = origName.endsWith(".mp3") || (audioBytes[0] === 0x49 && audioBytes[1] === 0x44 && audioBytes[2] === 0x33) || (audioBytes[0] === 0xFF && (audioBytes[1] & 0xE0) === 0xE0);
        const isWav = origName.endsWith(".wav") || (audioBytes[0] === 0x52 && audioBytes[1] === 0x49 && audioBytes[2] === 0x46 && audioBytes[3] === 0x46);
        const upName = isMp3 ? "yuna_mic.mp3" : (isWav ? "yuna_mic.wav" : "yuna_mic.m4a");
        const upMime = isMp3 ? "audio/mpeg" : (isWav ? "audio/wav" : "audio/mp4");

        let rawStt = "";
        let sttMeta = { status: "usable", outcome: "ACCEPT", avg_logprob: null, no_speech_prob: null, compression_ratio: null, metadata_available: false };
        let lastSttErr = "";
        for (const k of sttKeys) {
          try {
            const gForm = new FormData();
            gForm.append("file", new Blob([audioBuf], { type: upMime }), upName);
            gForm.append("model", "whisper-large-v3");
            gForm.append("language", "ta");
            gForm.append("response_format", "verbose_json");
            gForm.append("temperature", "0");
            const contactsHint = String(form.get("contacts_hint") || "").trim().slice(0, 220);
            const basePrompt = "WhatsApp, YouTube, GPay, Google Pay, PhonePe, Maps, UI, APK, Settings, ஓபன் பண்ணு, கால் பண்ணு, டார்ச் ஆன், ஸ்கிரீன்ஷாட் எடு, ரிமைண்டர் வை, செட்டிங்ஸ் செக் பண்ணு, ஆப்ஸ் லிஸ்ட் எடு, நாமக்கல் லோடு, நோட் பண்ணு.";
            const fullPrompt = contactsHint ? (basePrompt + " " + contactsHint).slice(0, 420) : basePrompt;
            gForm.append("prompt", fullPrompt);
            const gr = await fetch("https://api.groq.com/openai/v1/audio/transcriptions", {
              method: "POST",
              headers: { "Authorization": "Bearer " + k },
              body: gForm
            });
            const gd = await gr.json();
            if (gr.ok && gd?.text) { rawStt = String(gd.text).trim(); sttMeta = extractGroqQuality(gd); break; }
            lastSttErr = gd?.error?.message || ("HTTP " + gr.status);
          } catch (se) { lastSttErr = se.message; }
        }
        if (!rawStt && env.AI) {
          try {
            // reuse audioBytes from above
            const cfStt = await env.AI.run("@cf/openai/whisper-large-v3-turbo", {
              audio: uint8ToBase64Safe(audioBytes),
              language: "ta",
              vad_filter: true,
              hallucination_silence_threshold: 0.5,
              initial_prompt: "WhatsApp, YouTube, GPay, Settings, ஓபன் பண்ணு, கால் பண்ணு, டார்ச் ஆன்"
            });
            if (cfStt?.text) rawStt = String(cfStt.text).trim();
          } catch (cfe) { lastSttErr = lastSttErr || cfe.message; }
        }
        if (!rawStt) return new Response(JSON.stringify({ ok: false, text: "", error: lastSttErr || "STT error" }), { headers: cors });
        if (/^(?:thank you for watching!?|thanks for watching!?|subscribe!?|amara\.org|mbc|நன்றி\.?)$/i.test(rawStt.trim())) {
          return new Response(JSON.stringify({ ok: true, text: "" }), { headers: cors });
        }
        if (sttMeta.outcome === "REJECT_OR_REPROMPT") {
          return new Response(JSON.stringify({ ok: true, text: "", raw_text: rawStt, quality: sttMeta }), { headers: cors });
        }
        let repairedStt = rawStt;
        if (hasNonTamilIndicScript(rawStt)) {
          repairedStt = await repairIndicScriptLeak(env, rawStt);
          if (!repairedStt || hasNonTamilIndicScript(repairedStt)) {
            return new Response(JSON.stringify({ ok: true, text: "", raw_text: rawStt, quality: { ...sttMeta, status: "script_rejected" } }), { headers: cors });
          }
        }
        const normStt = normalizeTamilRuntimeText(repairedStt);
        return new Response(JSON.stringify({ ok: true, text: normStt, raw_text: rawStt, quality: sttMeta }), { headers: cors });
      }

      // Master Storage: Notes Sync (/notes)
      if (u.pathname === "/notes") {
        if (!env.DB) return new Response(JSON.stringify({ ok: false, notes: [] }), { headers: cors });
        await env.DB.prepare("CREATE TABLE IF NOT EXISTS yuna_notes (id INTEGER PRIMARY KEY AUTOINCREMENT, sid TEXT, text TEXT, at TEXT, ts INTEGER)").run();
        if (request.method === "GET") {
          const rows = await env.DB.prepare("SELECT text, at FROM yuna_notes WHERE sid = ? ORDER BY id DESC LIMIT 40").bind(sidParam).all();
          return new Response(JSON.stringify({ ok: true, notes: rows?.results || [] }), { headers: cors });
        }
        if (request.method === "POST") {
          const body = await request.json();
          const s = String(body.sessionId || sidParam).replace(/[^a-zA-Z0-9_-]/g, "").slice(0, 40) || "default";
          if (body.op === "clear") {
            await env.DB.prepare("DELETE FROM yuna_notes WHERE sid = ?").bind(s).run();
            return new Response(JSON.stringify({ ok: true }), { headers: cors });
          }
          const txt = String(body.text || "").trim().slice(0, 500);
          const at = String(body.at || "").slice(0, 20);
          if (txt) await env.DB.prepare("INSERT INTO yuna_notes (sid, text, at, ts) VALUES (?, ?, ?, ?)").bind(s, txt, at, Date.now()).run();
          return new Response(JSON.stringify({ ok: true }), { headers: cors });
        }
      }

      // Master Storage: History Read (/history)
      if (u.pathname === "/history" && request.method === "GET") {
        if (!env.DB) return new Response(JSON.stringify({ ok: false, history: [] }), { headers: cors });
        await env.DB.prepare("CREATE TABLE IF NOT EXISTS yuna_memory (id INTEGER PRIMARY KEY AUTOINCREMENT, sid TEXT, role TEXT, content TEXT, ts INTEGER)").run();
        const rows = await env.DB.prepare("SELECT role, content, ts FROM yuna_memory WHERE sid = ? ORDER BY id DESC LIMIT 20").bind(sidParam).all();
        return new Response(JSON.stringify({ ok: true, history: (rows?.results || []).reverse() }), { headers: cors });
      }

      if (u.pathname === "/voice-turn" && request.method === "POST") {
        const vt = await request.json();
        const b64 = String(vt?.audio?.data_base64 || "").replace(/^data:[^;]+;base64,/, "");
        if (!b64) return new Response(JSON.stringify({ ok: false, reply: "ஆடியோ இல்லை.", actions: [], execution: { status: "not_executed" } }), { status: 400, headers: cors });
        const binStr = atob(b64);
        const bytes = new Uint8Array(binStr.length);
        for (let i = 0; i < binStr.length; i++) bytes[i] = binStr.charCodeAt(i);
        const sttRes = await runSttPipeline(env, bytes.buffer, bytes, vt?.contacts_hint || "");
        if (!sttRes.ok || sttRes.quality.outcome === "REJECT_OR_REPROMPT" || !sttRes.normalized) {
          return new Response(JSON.stringify({
            schema_version: "1.0",
            turn_id: vt.turn_id || crypto.randomUUID(),
            transcript: { raw: sttRes.raw || "", normalized: "", provider: sttRes.provider, quality: sttRes.quality },
            interpretation: { intent: "unclear_audio", slots: {}, missing_slots: [], requires_clarification: true },
            actions: [],
            reply: "சரியாகக் கேட்கவில்லை, மீண்டும் சொல்லுங்கள்.",
            tts_text: "சரியாகக் கேட்கவில்லை, மீண்டும் சொல்லுங்கள்.",
            execution: { status: "not_executed" }
          }), { headers: cors });
        }
        const internalReq = new Request("https://internal/", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ prompt: sttRes.normalized, sessionId: vt.session_id || "default", contacts: vt.contacts || [] })
        });
        const internalResp = await this.fetch(internalReq, env, ctx);
        const nluOut = await internalResp.json();
        nluOut.schema_version = "1.0";
        nluOut.turn_id = vt.turn_id || crypto.randomUUID();
        nluOut.transcript = { raw: sttRes.raw, normalized: sttRes.normalized, provider: sttRes.provider, quality: sttRes.quality };
        nluOut.tts_text = formatTtsTamil(nluOut.reply);
        return new Response(JSON.stringify(nluOut), { headers: cors });
      }

      if (request.method !== "POST") return new Response(JSON.stringify({ error: "POST only" }), { status: 405, headers: cors });

      const { prompt = "", sessionId = "default", contacts = [], pending_intent_id = null } = await request.json();
      const rawPrompt = String(prompt).trim();
      const q = normalizeTamilRuntimeText(rawPrompt);
      const skipTier0 = u.searchParams.get("no_tier0") === "1";
      const tier0 = skipTier0 ? null : matchTier0FastPath(q);
      if (tier0) {
        return new Response(JSON.stringify(tier0), { headers: cors });
      }
      const sid = String(sessionId).replace(/[^a-zA-Z0-9_-]/g, "").slice(0, 40) || "default";
      if (env.DB && q.length > 0 && q.length < 30 && !/டார்ச்|வைஃபை|பிரைட்னஸ்|Brightness|WiFi|Mobile Data|நேரம்|தேதி|லோடு|அலாரம்|டைமர்|ஸ்கிரீன்ஷாட்/.test(q)) {
        try {
          const pRow = await env.DB.prepare("SELECT id, intent, expires_at FROM yuna_pending_intents WHERE sid = ? AND status = 'awaiting_slot' ORDER BY created_at DESC LIMIT 1").bind(sid).first();
          if (pRow && pRow.expires_at > Date.now()) {
            if (/^(?:வேண்டாம்|கேன்சல்|நிறுத்து|cancel|no)$/i.test(q)) {
              await env.DB.prepare("UPDATE yuna_pending_intents SET status = 'cancelled' WHERE id = ?").bind(pRow.id).run();
              return new Response(JSON.stringify({ reply: "சரி, அந்தச் செயலை ரத்து செய்துவிட்டேன்.", actions: [], pending_intent: { id: pRow.id, status: "cancelled" }, execution: { status: "not_executed" } }), { headers: cors });
            }
            if (pRow.intent === "contacts.call") {
              await env.DB.prepare("UPDATE yuna_pending_intents SET status = 'resolved' WHERE id = ?").bind(pRow.id).run();
              const tName = q.replace(/(?:க்கு|உக்கு|ஐ)$/g, "").trim();
              return new Response(JSON.stringify({
                reply: `${tName}-க்கு கால் பண்றேன்.`,
                actions: [{ action: "DIAL", target: tName }],
                interpretation: { intent: "contacts.call", slots: { contact_id: tName }, missing_slots: [], requires_clarification: false },
                pending_intent: { id: pRow.id, status: "resolved" },
                execution: { status: "proposed_for_android" }
              }), { headers: cors });
            }
          }
        } catch (_) {}
      }
      if (!q) return new Response(JSON.stringify({ reply: "சொல்லுங்கள் கண்ணன், என்ன உதவி வேண்டும்?", actions: [] }), { headers: cors });

      let historyMsgs = [];
      if (env.DB) {
        try {
          await env.DB.prepare("CREATE TABLE IF NOT EXISTS yuna_memory (id INTEGER PRIMARY KEY AUTOINCREMENT, sid TEXT, role TEXT, content TEXT, ts INTEGER)").run();
          const rows = await env.DB.prepare("SELECT role, content FROM yuna_memory WHERE sid = ? ORDER BY id DESC LIMIT 6").bind(sid).all();
          if (rows?.results?.length) historyMsgs = rows.results.reverse().map(r => ({ role: r.role, content: r.content }));
        } catch (_) {}
      }

      // Personalized YUNA System Prompt (v41: Zero-Ambiguity Generalizable Ontology)
      const sys = [
        "You are YUNA, Kannan's rugged Executive PA in Tamil Nadu. Zero sugarcoating, zero fake success.",
        "Dialect/NLU: 'கீது/கீதா'=இருக்கு/இருக்கா (unless கீதாவுக்கு); 'Mobile Data/நெட்/டேட்டா'=MOBILE_DATA_ON/OFF; 'WiFi/வைஃபை'=WIFI_ON/OFF; 'ஹாட்ஸ்பாட் அணை'=HOTSPOT_OFF; 'Bluetooth ஆஃப்/அணை'=BLUETOOTH_OFF; 'மூடு/ஆஃப்'=OFF; 'Brightness/வெளிச்சம்'+கூட்டு/ஏத்து=BRIGHTNESS_UP, குறை/கம்மி/இறக்கு=BRIGHTNESS_DOWN; 'Volume/சவுண்ட்'+கூட்டு/ஏத்து=VOLUME_UP, ஃபுல்=VOLUME_MAX; 'மியூட்/சைலன்ட்'=RINGER_SILENT; 'நார்மல் மோட்/சைலன்ட் எடு'=RINGER_NORMAL; 'ஸ்பீக்கருக்கு மாத்து'=SPEAKER_ON; 'ஸ்பீக்கர் ஆஃப்'=SPEAKER_OFF; 'Vibrate'=RINGER_VIBRATE; 'யார் கால் பண்றா'=WHO_IS_CALLING (never DIAL); 'ஓபன் ஆன ஆப்ஸ்/ரீசன்ட் ஆப்ஸ்'=GLOBAL_RECENTS; 'ஒரு நிமிஷம் டைமர்'=SET_TIMER(target='1m').",
        "PRIORITY & SLOT RULES: (1) EXPLICIT NOTE OVERRIDES LOADS: If user says 'நோட்/எழுது/குறிச்சு வை/note vei', ALWAYS emit NOTE(target=text), even if text mentions load/diesel! (2) If no note verb, any freight/load query ('நாமக்கல் லோடு', 'புது லோடு இருக்கா', 'load list') MUST emit LOADS(target=city_or_empty) — never ask which city when user wants all loads! (3) If a name OR role IS given (பாலா, முத்து, அம்மா, அப்பா, மனைவி, ஆபீஸ், டிரைவர்), ALWAYS emit DIAL(target=NameOrRole) — ONLY ask missing_slots:['contact_id'] for nameless pronouns ('அவனுக்கு','இவருக்கு'). (4) NEVER invent battery % in text; ALWAYS emit GET_BATTERY_TIME. (5) GPay/PhonePe/Paytm money requests -> ONLY OPEN_APP_DYNAMIC. (6) 'மேப்ஸ் ஓபன்' without destination -> OPEN_APP_DYNAMIC('Google Maps'); with destination -> OPEN_MAPS_NAV(target=place). 'Camera திற' -> OPEN_APP_DYNAMIC('Camera'). 'செட்டிங்ஸ்' -> OPEN_APP_DYNAMIC('Settings').",
        "Rules: Reply in clean Tamil script for Tamil/Tanglish (English for pure English), 1-2 crisp sentences. NEVER claim an OS action succeeded with actions:[].",
        "Strict JSON only: {\"intent\":\"os_action|query|chat\",\"canonical_intent\":\"string\",\"slots\":{},\"missing_slots\":[],\"requires_clarification\":false,\"reply\":\"...\",\"actions\":[{\"action\":\"ACTION_NAME\",\"target\":\"...\"}]}",
        "Actions: OPEN_APP_DYNAMIC(target), OPEN_MAPS_NAV(target), GLOBAL_BACK, GLOBAL_HOME, GLOBAL_RECENTS, CLEAR_ALL_APPS, OPEN_NOTIFICATIONS, OPEN_QUICK_SETTINGS, LOCK_SCREEN, SCREENSHOT, SCROLL_DOWN, SCROLL_UP, TORCH_ON, TORCH_OFF, WIFI_ON, WIFI_OFF, MOBILE_DATA_ON, MOBILE_DATA_OFF, HOTSPOT_ON, HOTSPOT_OFF, BLUETOOTH_ON, BLUETOOTH_OFF, BRIGHTNESS_UP, BRIGHTNESS_DOWN, VOLUME_UP, VOLUME_DOWN, VOLUME_MAX, RINGER_SILENT, RINGER_VIBRATE, RINGER_NORMAL, DIAL(target), SPEAKER_ON, SPEAKER_OFF, WHO_IS_CALLING, GET_BATTERY_TIME, SET_ALARM(target), SET_TIMER(target), NOTE(target), LOADS(target), VISION(target)."
      ].join(" ");

      const istNow = getIstNowInfo();
      const needsWeather = /மழை|வானிலை|weather|rain|வெயில்/.test(q);
      const needsWeb = /செய்தி|news|விலை|price|gold|தங்கம்|டீசல்|diesel|petrol|score|match|இப்போது என்ன/i.test(q);
      const [liveWeather, liveWeb] = await Promise.all([
        needsWeather ? fetchLiveWeatherSnippet(q) : Promise.resolve(""),
        needsWeb ? fetchLiveWebSnippet(q) : Promise.resolve("")
      ]);
      const topContacts = pickRelevantContacts(q, contacts, 5);
      const contactHint = topContacts.length
        ? " Contacts: " + topContacts.join(", ") + "."
        : "";
      const slimHistory = historyMsgs.slice(-4).map(m => ({ role: m.role, content: String(m.content || "").slice(0, 120) }));
      const istHint = ` Current IST: ${istNow.dateTa}, ${istNow.dayTa}, ${istNow.timeStr}. ${liveWeather} ${liveWeb}`.trim();
      const messages = [{ role: "system", content: sys + " " + istHint + contactHint }, ...slimHistory, { role: "user", content: q }];

      const isDeepTask = env.NVIDIA_API_KEY && (
        /code|python|kotlin|script|sql|analyze|விளக்கு|காரணம்|விவரமாக|ஒப்பிடு|திட்டம்|கணக்கு|வட்டி|contract|proposal/i.test(q) ||
        (q.length > 140 && !/ஓபன்|திற|open|கால்|call|டார்ச்|torch|அலாரம்|alarm|லோடு|load/i.test(q))
      );

      let rawText = "";
      const errLog = [];
      if (isDeepTask) {
        rawText = await askNvidiaNim(env.NVIDIA_API_KEY, messages, 900, errLog);
        if (!rawText && getGroqKeys(env).length) rawText = await askGroq(env, messages, errLog);
      } else {
        if (getGroqKeys(env).length) rawText = await askGroq(env, messages, errLog);
        if (!rawText && env.NVIDIA_API_KEY) rawText = await askNvidiaNim(env.NVIDIA_API_KEY, messages, 700, errLog);
      }
      if (!rawText && env.AI) {
        for (const cfModel of ["@cf/openai/gpt-oss-120b", "@cf/qwen/qwen3-30b-a3b-fp8", "@cf/meta/llama-4-scout-17b-16e-instruct"]) {
          try {
            const cfRes = await env.AI.run(cfModel, { messages, max_tokens: 800 });
            const respVal = cfRes?.response;
            rawText = (typeof respVal === "string") ? respVal : (respVal ? JSON.stringify(respVal) : "");
            if (rawText) break;
            errLog.push(`cf(${cfModel}):empty`);
          } catch (cfe) { errLog.push(`cf(${cfModel}):${cfe?.message || "err"}`); }
        }
      }
      if (!rawText) return new Response(JSON.stringify({ reply: "Cloud AI தயாராக இல்லை.", actions: [], debug_errors: errLog }), { headers: cors });

      const rawStr = (typeof rawText === "string") ? rawText : JSON.stringify(rawText);
      const cleaned = rawStr.replace(/^```json\s*/i, "").replace(/```$/i, "").trim();
      const jStart = cleaned.indexOf("{"), jEnd = cleaned.lastIndexOf("}");
      const jsonSlice = (jStart !== -1 && jEnd > jStart) ? cleaned.slice(jStart, jEnd + 1) : cleaned;
      let reply = cleaned;
      let actions = [];
      let canonicalIntent = "chat";
      let slots = {};
      let missingSlots = [];
      let requiresClarification = false;
      try {
        const parsed = JSON.parse(jsonSlice);
        reply = parsed.reply || cleaned;
        canonicalIntent = String(parsed.canonical_intent || parsed.intent || "chat");
        if (parsed.slots && typeof parsed.slots === "object") slots = parsed.slots;
        if (Array.isArray(parsed.missing_slots)) missingSlots = parsed.missing_slots;
        requiresClarification = Boolean(parsed.requires_clarification) || missingSlots.length > 0;
        if (Array.isArray(parsed.actions)) actions = parsed.actions.slice(0, 4).filter(a => a && a.action && a.action !== "NONE");
        else if (parsed.action && parsed.action !== "NONE") actions = [{ action: parsed.action, target: parsed.target || "" }];
      } catch (_) {
        const m = cleaned.match(/"reply"\s*:\s*"([^"]+)/);
        if (m && m[1]) reply = m[1];
      }
      if (actions.length === 0 && /gpay|google\s*pay|phonepe|paytm|ஜிபே|போன்பே|பேடிஎம்/i.test(q) && /ஓபன்|திற|ஓப்பேன்|open/i.test(reply)) {
        const upiApp = /phonepe|போன்பே/i.test(q) ? "PhonePe" : (/paytm|பேடிஎம்/i.test(q) ? "Paytm" : "GPay");
        actions = [{ action: "OPEN_APP_DYNAMIC", target: upiApp }];
      }
      const mappedOntology = mapActionsToOntology(actions, canonicalIntent);
      let pendingIntentOut = null;
      if (env.DB && requiresClarification && missingSlots.length > 0) {
        const pid = crypto.randomUUID();
        const exp = Date.now() + 180000;
        pendingIntentOut = { id: pid, status: "awaiting_slot", intent: mappedOntology.intent, missing_slots: missingSlots };
        if (ctx && typeof ctx.waitUntil === "function") {
          ctx.waitUntil((async () => {
            try {
              await env.DB.prepare("CREATE TABLE IF NOT EXISTS yuna_pending_intents (id TEXT PRIMARY KEY, sid TEXT, intent TEXT, slots_json TEXT, missing_slots_json TEXT, status TEXT, expires_at INTEGER, created_at INTEGER)").run();
              await env.DB.prepare("INSERT INTO yuna_pending_intents (id, sid, intent, slots_json, missing_slots_json, status, expires_at, created_at) VALUES (?, ?, ?, ?, ?, 'awaiting_slot', ?, ?)")
                .bind(pid, sid, mappedOntology.intent, JSON.stringify(slots), JSON.stringify(missingSlots), exp, Date.now()).run();
            } catch (_) {}
          })());
        }
      }
      let execStatus = actions.length > 0 ? "proposed_for_android" : "not_executed";
      if (actions.length === 0 && !requiresClarification && /(?:ஆன் பண்ணிட்டேன்|ஆஃப் பண்ணிட்டேன்|செயல்படுத்தப்பட்டுவிட்டது|கூட்டிட்டேன்|குறைச்சிட்டேன்|போட்டுட்டேன்)/.test(reply)) {
        execStatus = "blocked_unverified_claim";
      }

      if (env.DB && ctx && typeof ctx.waitUntil === "function") {
        const now = Date.now();
        ctx.waitUntil((async () => {
          try {
            await env.DB.prepare("INSERT INTO yuna_memory (sid, role, content, ts) VALUES (?, 'user', ?, ?)").bind(sid, q.slice(0, 300), now).run();
            await env.DB.prepare("INSERT INTO yuna_memory (sid, role, content, ts) VALUES (?, 'assistant', ?, ?)").bind(sid, reply.slice(0, 300), now + 1).run();
            await env.DB.prepare("DELETE FROM yuna_memory WHERE sid = ? AND id NOT IN (SELECT id FROM yuna_memory WHERE sid = ? ORDER BY id DESC LIMIT 24)").bind(sid, sid).run();
          } catch (_) {}
        })());
      }

      return new Response(JSON.stringify({
        reply,
        actions,
        transcript: { raw: rawPrompt, normalized: q },
        interpretation: {
          intent: mappedOntology.intent,
          ontology_actions: mappedOntology.items,
          slots,
          missing_slots: missingSlots,
          requires_clarification: requiresClarification
        },
        pending_intent: pendingIntentOut,
        execution: { status: execStatus }
      }), { headers: cors });
    } catch (e) {
      return new Response(JSON.stringify({ reply: "Cloud பிழை: " + e.message, actions: [] }), { status: 200, headers: cors });
    }
  }
};

async function synthEdgeTts(text, voice) {
  const TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
  const connId = crypto.randomUUID().replace(/-/g, "");
  const wsUrl = `https://api.msedgeservices.com/tts/cognitiveservices/websocket/v1?Ocp-Apim-Subscription-Key=${TRUSTED_TOKEN}&ConnectionId=${connId}`;

  const resp = await fetch(wsUrl, {
    headers: {
      "Upgrade": "websocket",
      "Origin": "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold",
      "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"
    }
  });
  const ws = resp.webSocket;
  if (!ws) throw new Error("No WebSocket");
  ws.accept();

  const safeText = text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  const reqId = crypto.randomUUID().replace(/-/g, "");
  const ts = new Date().toISOString();
  const lang = voice.startsWith("ta-") ? "ta-IN" : "en-US";

  return await new Promise((resolve, reject) => {
    const chunks = [];
    let totalLen = 0;
    const timer = setTimeout(() => { try { ws.close(); } catch (_) {} reject(new Error("TTS timeout")); }, 6500);

    ws.addEventListener("message", (ev) => {
      if (typeof ev.data === "string") {
        if (ev.data.includes("Path:turn.end")) {
          clearTimeout(timer);
          try { ws.close(); } catch (_) {}
          const out = new Uint8Array(totalLen);
          let off = 0;
          for (const c of chunks) { out.set(c, off); off += c.byteLength; }
          resolve(out);
        }
      } else if (ev.data instanceof ArrayBuffer) {
        const view = new Uint8Array(ev.data);
        if (view.byteLength > 2) {
          const hdrLen = (view[0] << 8) | view[1];
          const bodyOff = 2 + hdrLen;
          if (bodyOff < view.byteLength) {
            const slice = view.slice(bodyOff);
            chunks.push(slice);
            totalLen += slice.byteLength;
          }
        }
      }
    });
    ws.addEventListener("error", () => { clearTimeout(timer); reject(new Error("WS error")); });

    ws.send(`X-Timestamp:${ts}\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}`);
    ws.send(`X-RequestId:${reqId}\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${ts}Z\r\nPath:ssml\r\n\r\n<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='${lang}'><voice name='${voice}'><prosody pitch='+0Hz' rate='+0%' volume='+0%'>${safeText}</prosody></voice></speak>`);
  });
}

function getGroqKeys(env) {
  const pool = String(env.GROQ_API_KEYS || "").split(",").map(s => s.trim()).filter(Boolean);
  if (env.GROQ_API_KEY && !pool.includes(env.GROQ_API_KEY)) pool.unshift(env.GROQ_API_KEY);
  return pool;
}

async function discoverGroqChatModels(key) {
  try {
    const r = await fetch("https://api.groq.com/openai/v1/models", { headers: { "Authorization": "Bearer " + key } });
    if (!r.ok) return [];
    const d = await r.json();
    return (d?.data || [])
      .map(m => m.id)
      .filter(id => id && !/whisper|guard|tts|safeguard|orpheus/i.test(id))
      .slice(0, 5);
  } catch (_) { return []; }
}

async function askGroq(envOrKey, messages, errLog = []) {
  const keys = (typeof envOrKey === "string") ? [envOrKey] : getGroqKeys(envOrKey);
  let models = ["openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3-32b", "llama-3.3-70b-versatile"];
  let triedDynamic = false;
  for (let mi = 0; mi < models.length; mi++) {
    const model = models[mi];
    for (const key of keys) {
      for (const useJson of [true, false]) {
        try {
          const payload = { model, temperature: 0.15, frequency_penalty: 0.35, max_tokens: 1400, messages };
          if (/gpt-oss/i.test(model)) payload.reasoning_effort = "low";
          if (useJson) payload.response_format = { type: "json_object" };
          const r = await fetch("https://api.groq.com/openai/v1/chat/completions", {
            method: "POST",
            headers: { "Authorization": "Bearer " + key, "Content-Type": "application/json" },
            body: JSON.stringify(payload)
          });
          if (r.status === 400 && useJson) { errLog.push(`groq(${model}):400_json_retry`); continue; }
          if (!r.ok) {
            errLog.push(`groq(${model}):${r.status}`);
            if (r.status === 429) await new Promise(res => setTimeout(res, 300));
            if (r.status === 404 && !triedDynamic && mi === models.length - 1) {
              triedDynamic = true;
              const live = await discoverGroqChatModels(key);
              for (const lm of live) { if (!models.includes(lm)) models.push(lm); }
            }
            break;
          }
          const d = await r.json();
          const txt = d?.choices?.[0]?.message?.content || "";
          if (txt) return txt;
          errLog.push(`groq(${model}):empty`);
        } catch (ge) { errLog.push(`groq(${model}):${ge?.message || "err"}`); }
        break;
      }
    }
  }
  return "";
}
async function _legacyAskGroq(key, messages) {
  try {
    const r = await fetch("https://api.groq.com/openai/v1/chat/completions", {
      method: "POST",
      headers: { "Authorization": "Bearer " + key, "Content-Type": "application/json" },
      body: JSON.stringify({ model: "llama-3.3-70b-versatile", temperature: 0.3, max_tokens: 900, messages })
    });
    const d = await r.json();
    return d?.choices?.[0]?.message?.content || "";
  } catch (ne) { errLog.push(`nim:${ne?.message || "err"}`); return ""; }
}


const PHONETIC_STT_RULES = [
  [/இன்னுக்கு\s*டேதி/g, "இன்னைக்கு தேதி"],
  [/பாக்கம்புடிதான்|பார்க்கம்புடிதான்/g, "பார்க்க முடியுமா"],
  [/யூ\s*வையில/g, "UI-ல"],
  [/ரிமென்டர்/g, "ரிமைண்டர்"],
  [/அப்பிரிக்கும்\s*லிஸ்ட்டிடு/g, "ஆப்ஸ் லிஸ்ட் எடு"],
  [/செட்க்க\s*மின்னி/g, "செக் பண்ணி"],
  [/அவலாபல/g, "available-ஆ"],
  [/(?:^|\s)(?:ஏல|மச்சி|மச்சான்|நைனா|அப்பு|மாப்பிள்ளை|அண்ணாச்சி|ஏங்க|ப்ரோ|டா|டி|பா|bro|machi|machan)(?=[\s,\.\?!]|$)/gi, " "],
  [/(லோடு|வண்டி|பாரம்|ஏதாச்சும்|எதுவும்|இடம்|ஆள்|வேலை)\s+கீதா(?=\s|$|\?)/g, "$1 இருக்கா"],
  [/கீதா(?=\s+(?:பாரு|சொல்லு|செக்|என்ன)\b|\s*\?|$)/g, "இருக்கா"],
  [/கீது/g, "இருக்கு"],
  [/இன்னா|என்னல/g, "என்ன"],
  [/போல(?=\s|$)/g, "போ"],
  [/வால(?=\s|$)/g, "வா"],
  [/பண்ணுங்கோ|பண்ணுல/g, "பண்ணு"],
  [/இருக்குதுங்க/g, "இருக்கு"],
  [/போங்கோ|போப்பா/g, "போ"],
  [/வாப்பா/g, "வா"],
  [/வுடு/g, "விடு"],
  [/ஓபன்\s*(?:மணி|பன்னு|பண்ண)/g, "ஓபன் பண்ணு"],
  [/open\s*pan+u|open\s*money/gi, "ஓபன் பண்ணு"],
  [/call\s*money/gi, "கால் பண்ணு"],
  [/on\s*money/gi, "ஆன் பண்ணு"],
  [/off\s*money/gi, "ஆஃப் பண்ணு"],
  [/(?:இட்\s*டு|யூ\s*டு)/gi, "YouTube"],
  [/ஆண்\s*(?:பண்ணு|பன்னு|மணி)/g, "ஆன் பண்ணு"],
  [/ஆன்\s*(?:மணி|பன்னு)/g, "ஆன் பண்ணு"],
  [/ஆப்\s*(?:மணி|பன்னு|பண்ணு)/g, "ஆஃப் பண்ணு"],
  [/ஆஃப்\s*(?:மணி|பன்னு)/g, "ஆஃப் பண்ணு"],
  [/கால்\s*(?:மணி|பன்னு|பண்ண)/g, "கால் பண்ணு"],
  [/டயல்\s*(?:மணி|பன்னு)|டையல்\s*(?:பண்ணு|பன்னு)/g, "டயல் பண்ணு"],
  [/செட்\s*(?:மணி|பன்னு)/g, "செட் பண்ணு"],
  [/சேவ்\s*(?:மணி|பன்னு)/g, "சேவ் பண்ணு"],
  [/நோட்\s*(?:மணி|பன்னு)/g, "நோட் பண்ணு"],
  [/க்ளோஸ்\s*(?:மணி|பன்னு)|குளோஸ்\s*(?:பண்ணு|பன்னு)/g, "ஆஃப் பண்ணு"],
  [/கிளியர்\s*(?:மணி|பன்னு)|க்ளியர்\s*(?:பண்ணு|பன்னு)/g, "கிளியர் பண்ணு"],
  [/லாக்\s*(?:மணி|பன்னு)/g, "லாக் பண்ணு"],
  [/கட்\s*(?:மணி|பன்னு)/g, "கட் பண்ணு"],
  [/(போன்|கால்|ரூட்|மேப்|டார்ச்|லைட்|அலாரம்|டைமர்|மோட்)\s*(?:போது|போட்டு)/g, "$1 போடு"],
  [/(ஸ்கிரீன்ஷாட்|ஸ்க்ரீன்\s*ஷாட்|கேலரி|கால்குலேட்டர்)\s*(?:ஏடு|எது)/g, "$1 எடு"],
  [/ஸ்க்ரீன்\s*ஷாட்|ஸ்கிரீன்\s*ஷாட்/g, "ஸ்கிரீன்ஷாட்"],
  [/பாக்\s*போ|பின்னால\s*போ/g, "பேக் போ"],
  [/ஹோம்\s*ஸ்க்ரீன்/g, "ஹோம் ஸ்கிரீன்"],
  [/தீர(?=\s|$)/g, "திற"],
  [/வலி\s*காட்டு/g, "வழி காட்டு"],
  [/குறிச்சி\s*வை|குறிச்சு\s*வையி/g, "குறிச்சு வை"],
  [/வையி(?=\s|$)/g, "வை"],
  [/ஃபிளாஷ்\s*லைட்|பிளாஷ்\s*லைட்/g, "டார்ச்"],
  [/லோட்(?=\s|$)/g, "லோடு"],
  [/வாட்ஸ்\s*ஆப்|வாட்சப்|வாட்ஸ்அப்|வட்ஸ்அப்/g, "WhatsApp"],
  [/யூ\s*டியூப்|யூட்யூப்|யூடியூப்|யூடுப்/g, "YouTube"],
  [/ஜி\s*பே|ஜீ\s*பே|ஜிபே|கூகுள்\s*பே/g, "GPay"],
  [/போன்\s*பே|போன்பே|போன்பை/g, "PhonePe"],
  [/பே\s*டி\s*எம்|பேடிஎம்/g, "Paytm"],
  [/கூகுள்\s*மேப்ஸ்?|மேப்ஸ்/g, "Google Maps"],
  [/இன்ஸ்டா\s*கிராம்|இன்ஸ்டாகிராம்|இன்ஸ்டா/g, "Instagram"],
  [/வைபை|வை\s*பை|வை\s*ஃபை/g, "WiFi"],
  [/\b(?:ethu|yethu)\b/gi, "ஏத்து"],
  [/\b(?:moodu|mudu)\b/gi, "மூடு"],
  [/ஸ்கிரீன்\s*ஆஃப்|ஸ்க்ரீன்\s*ஆஃப்|screen\s*off/gi, "ஸ்கிரீன் லாக்"],
  [/^ஓம்\s*போ$/g, "ஹோம் போ"],
  [/அல்லாரம்/g, "அலாரம்"],
  [/கேமரா|camera/gi, "Camera"],
  [/ஹாட்\s*ஸ்பாட்|ஹாட்ஸ்பொட்/g, "Hotspot"],
  [/பிளைட்\s*மோட்|ஃப்ளைட்\s*மோட்|ஏரோபிளேன்\s*மோட்/g, "Flight Mode"],
  [/ப்ளூடூத்|புளூ\s*டூத்|புளூடூத்/g, "Bluetooth"],
  [/நோட்டிஃபிகேஷன்|நோட்டிபிகேஷன்|நோடிபிகேஷன்/g, "Notifications"],
  [/ப்ரைட்னஸ்|பிரைட்\s*னஸ்|பிரைட்னஸ்/g, "Brightness"],
  [/ஸ்பீக்கர்/g, "Speaker"],
  [/வைப்ரேட்/g, "Vibrate"],
  [/சைலன்ட்|சைலென்ட்/g, "Silent"],
  [/(?:^|\s)நெட்(?=\s+(?:ஆன்|ஆஃப்|ஆப்|on|off|கட்|போடு|வேண்டாம்))/gi, " Mobile Data"],
  [/ரீசன்ட்\s*ஆப்ஸ்|ரீசென்ட்\s*ஆப்ஸ்|மினிமைஸ்\s*ஆப்ஸ்/g, "ரீசன்ட் ஆப்ஸ்"],
  [/வால்யூம்|வால்யும்/g, "வால்யூம்"]
];

function normalizeTamilRuntimeText(input) {
  let s = String(input || "").trim();
  if (!s) return s;
  for (const [re, rep] of PHONETIC_STT_RULES) {
    try { s = s.replace(re, rep); } catch (_) {}
  }
  return s.replace(/\s+/g, " ").trim();
}


function pickRelevantContacts(query, contacts, limit = 5) {
  if (!Array.isArray(contacts) || !contacts.length) return [];
  const qLow = String(query || "").toLowerCase();
  const matched = contacts.filter(c => {
    const parts = String(c).toLowerCase().split(/\s+/);
    return parts.some(p => p.length >= 2 && qLow.includes(p));
  });
  return (matched.length ? matched : contacts).slice(0, limit);
}

function matchTier0FastPath(q) {
  const s = String(q || "").trim();
  const low = s.toLowerCase();
  if (/^(டார்ச்|torch)\s*(ஆன்|on)(\s*பண்ணு)?$/i.test(low)) return { reply: "டார்ச் ஆன் பண்ணிட்டேன்.", actions: [{ action: "TORCH_ON", target: "" }] };
  if (/^(டார்ச்|torch)\s*(ஆஃப்|ஆப்|off)(\s*பண்ணு)?$/i.test(low)) return { reply: "டார்ச் ஆஃப் பண்ணிட்டேன்.", actions: [{ action: "TORCH_OFF", target: "" }] };
  if (/^(ஸ்கிரீன்ஷாட்|screenshot)(\s*எடு)?$/i.test(low)) return { reply: "ஸ்கிரீன்ஷாட் எடுத்துட்டேன்.", actions: [{ action: "SCREENSHOT", target: "" }] };
  if (/^(பேக்|back)\s*(போ|po)?$/i.test(low)) return { reply: "பின்னாடி போறேன்.", actions: [{ action: "GLOBAL_BACK", target: "" }] };
  if (/^(ஹோம்|home)(\s*ஸ்கிரீன்)?\s*(போ|po)?$/i.test(low)) return { reply: "ஹோம் ஸ்கிரீனுக்கு வந்தாச்சு.", actions: [{ action: "GLOBAL_HOME", target: "" }] };
  const appPairs = [
    ["WhatsApp", /^(?:whatsapp|வாட்ஸ்அப்)\s*(?:ஓபன் பண்ணு|திற|open|போடு)$/i],
    ["YouTube", /^(?:youtube|யூடியூப்)\s*(?:ஓபன் பண்ணு|திற|open|போடு)$/i],
    ["YouTube", /^(?:open|start)\s*youtube\.?$/i],
    ["WhatsApp", /^(?:open|start)\s*whatsapp\.?$/i],
    ["Google Pay", /^(?:gpay|google pay)\s*(?:ஓபன் பண்ணு|திற|open)$/i],
    ["PhonePe", /^(?:phonepe)\s*(?:ஓபன் பண்ணு|திற|open)$/i],
    ["Google Maps", /^(?:google maps|மேப்ஸ்)\s*(?:ஓபன் பண்ணு|திற|open)$/i],
    ["Instagram", /^(?:instagram)\s*(?:ஓபன் பண்ணு|திற|open)$/i],
    ["Settings", /^(?:settings|செட்டிங்ஸ்)\s*(?:ஓபன் பண்ணு|திற|open)$/i]
  ];
  for (const [app, re] of appPairs) {
    if (re.test(low)) return { reply: `${app} ஓபன் பண்றேன்.`, actions: [{ action: "OPEN_APP_DYNAMIC", target: app }] };
  }
  if (/^(?:இன்னைக்கு|இன்றைய)?\s*(?:தேதி|date|கிழமை|நாள்)(\s*என்ன)?\??$/i.test(low)) {
    const ist = getIstNowInfo();
    return { reply: `இன்றைய தேதி ${ist.dateTa}, ${ist.dayTa}.`, actions: [] };
  }
  if (/^(?:இப்போ)?\s*(?:நேரம்|டைம்|time)(\s*என்ன)?\??$/i.test(low)) {
    const ist = getIstNowInfo();
    return { reply: `இப்போதைய நேரம் ${ist.timeStr} (${ist.dayTa}).`, actions: [] };
  }
  return null;
}


function uint8ToBase64Safe(u8) {
  let out = "";
  const chunk = 0x8000;
  for (let i = 0; i < u8.length; i += chunk) {
    out += String.fromCharCode.apply(null, u8.subarray(i, i + chunk));
  }
  return btoa(out);
}


function getIstNowInfo() {
  const istMs = Date.now() + 19800000;
  const d = new Date(istMs);
  const daysTa = ["ஞாயிற்றுக்கிழமை", "திங்கட்கிழமை", "செவ்வாய்க்கிழமை", "புதன்கிழமை", "வியாழக்கிழமை", "வெள்ளிக்கிழமை", "சனிக்கிழமை"];
  const monthsTa = ["ஜனவரி", "பிப்ரவரி", "மார்ச்", "ஏப்ரல்", "மே", "ஜூன்", "ஜூலை", "ஆகஸ்ட்", "செப்டம்பர்", "அக்டோபர்", "நவம்பர்", "டிசம்பர்"];
  const dayTa = daysTa[d.getUTCDay()];
  const dateTa = `${monthsTa[d.getUTCMonth()]} ${d.getUTCDate()}, ${d.getUTCFullYear()}`;
  const hh = String(d.getUTCHours()).padStart(2, "0");
  const mm = String(d.getUTCMinutes()).padStart(2, "0");
  return { dateTa, dayTa, timeStr: `${hh}:${mm} IST` };
}

async function fetchLiveWeatherSnippet(q) {
  try {
    const low = String(q || "").toLowerCase();
    let lat = 8.7139, lon = 77.7567, city = "திருநெல்வேலி";
    if (/chennai|சென்னை/.test(low)) { lat = 13.0827; lon = 80.2707; city = "சென்னை"; }
    else if (/madurai|மதுரை/.test(low)) { lat = 9.9252; lon = 78.1198; city = "மதுரை"; }
    else if (/coimbatore|கோவை/.test(low)) { lat = 11.0168; lon = 76.9558; city = "கோயம்புத்தூர்"; }
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), 1800);
    const r = await fetch(`https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}&current=temperature_2m,precipitation,weather_code&timezone=Asia%2FKolkata`, { signal: ctrl.signal });
    clearTimeout(t);
    const d = await r.json();
    const cur = d?.current;
    if (!cur) return "";
    return `[நேரடி வானிலை - ${city}: ${cur.temperature_2m}°C, மழை அளவு ${cur.precipitation}mm]`;
  } catch (_) { return ""; }
}


async function askNvidiaNim(rawKey, messages, maxTokens = 850, errLog = []) {
  const key = String(rawKey || "").trim().replace(/^Nvapi-/, "nvapi-");
  if (!key) return "";
  const nimModels = ["nvidia/nemotron-3-ultra-550b-a55b", "nvidia/nemotron-3-super-120b-a12b", "deepseek-ai/deepseek-v3.2"];
  for (const model of nimModels) {
    try {
      const ctrl = new AbortController();
      const t = setTimeout(() => ctrl.abort(), 14000);
      const r = await fetch("https://integrate.api.nvidia.com/v1/chat/completions", {
        method: "POST",
        signal: ctrl.signal,
        headers: { "Authorization": "Bearer " + key, "Content-Type": "application/json" },
        body: JSON.stringify({ model, temperature: 0.2, max_tokens: Math.max(maxTokens, 1200), ...( /nemotron/i.test(model) ? { chat_template_kwargs: { enable_thinking: false } } : {} ), messages })
      });
      clearTimeout(t);
      if (!r.ok) {
        errLog.push(`nim(${model}):${r.status}`);
        if (r.status === 401 || r.status === 403 || r.status === 410) return "";
        continue;
      }
      const d = await r.json();
      const txt = d?.choices?.[0]?.message?.content || "";
      if (txt) return txt;
    } catch (ne) { errLog.push(`nim(${model}):${ne?.message || "err"}`); }
  }
  return "";
}

async function askNvidiaVision(rawKey, content) {
  const key = String(rawKey || "").trim().replace(/^Nvapi-/, "nvapi-");
  if (!key) return "";
  try {
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), 11000);
    const r = await fetch("https://integrate.api.nvidia.com/v1/chat/completions", {
      method: "POST",
      signal: ctrl.signal,
      headers: { "Authorization": "Bearer " + key, "Content-Type": "application/json" },
      body: JSON.stringify({ model: "meta/llama-3.2-11b-vision-instruct", temperature: 0.2, max_tokens: 600, messages: [{ role: "user", content }] })
    });
    clearTimeout(t);
    if (!r.ok) return "";
    const d = await r.json();
    return d?.choices?.[0]?.message?.content || "";
  } catch (_) { return ""; }
}

async function fetchLiveWebSnippet(q) {
  try {
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), 2000);
    const url = `https://news.google.com/rss/search?q=${encodeURIComponent(String(q).slice(0, 80))}&hl=ta-IN&gl=IN&ceid=IN:ta`;
    const r = await fetch(url, { signal: ctrl.signal, headers: { "User-Agent": "Mozilla/5.0" } });
    clearTimeout(t);
    if (!r.ok) return "";
    const xml = await r.text();
    const titles = [...xml.matchAll(/<item>[\s\S]*?<title><!\[CDATA\[(.*?)\]\]><\/title>|<item>[\s\S]*?<title>(.*?)<\/title>/g)]
      .map(m => (m[1] || m[2] || "").replace(/<[^>]+>/g, "").trim())
      .filter(Boolean)
      .slice(0, 3);
    return titles.length ? `[நேரடி செய்தி/தகவல்: ${titles.join(" | ")}]` : "";
  } catch (_) { return ""; }
}


const ACTION_ONTOLOGY_V1 = {
  BRIGHTNESS_UP: { action_id: "device.brightness.set", side_effect: "device_change", direction: "up" },
  BRIGHTNESS_DOWN: { action_id: "device.brightness.set", side_effect: "device_change", direction: "down" },
  MOBILE_DATA_ON: { action_id: "device.connectivity.mobile_data", side_effect: "device_change", state: "on" },
  MOBILE_DATA_OFF: { action_id: "device.connectivity.mobile_data", side_effect: "device_change", state: "off" },
  WIFI_ON: { action_id: "device.connectivity.wifi", side_effect: "device_change", state: "on" },
  WIFI_OFF: { action_id: "device.connectivity.wifi", side_effect: "device_change", state: "off" },
  DIAL: { action_id: "contacts.call", side_effect: "external_communication" },
  WHO_IS_CALLING: { action_id: "calls.who_is_calling", side_effect: "read_only" },
  SPEAKER_ON: { action_id: "calls.speaker", side_effect: "device_change", state: "on" },
  SPEAKER_OFF: { action_id: "calls.speaker", side_effect: "device_change", state: "off" },
  RINGER_VIBRATE: { action_id: "device.ringer.set", side_effect: "device_change", mode: "vibrate" },
  RINGER_SILENT: { action_id: "device.ringer.set", side_effect: "device_change", mode: "silent" },
  RINGER_NORMAL: { action_id: "device.ringer.set", side_effect: "device_change", mode: "normal" },
  NOTE: { action_id: "notes.create", side_effect: "local_write" },
  LOADS: { action_id: "freight_load.query", side_effect: "read_only" },
  OPEN_APP_DYNAMIC: { action_id: "app.open", side_effect: "app_launch" },
  OPEN_MAPS_NAV: { action_id: "maps.navigate", side_effect: "app_launch" }
};

function mapActionsToOntology(actions, fallbackIntent) {
  const items = (actions || []).map(a => {
    const meta = ACTION_ONTOLOGY_V1[a.action] || { action_id: "legacy." + String(a.action || "unknown").toLowerCase(), side_effect: "device_change" };
    return { legacy_action: a.action, target: a.target || "", ...meta };
  });
  const intent = items.length ? items[0].action_id : (fallbackIntent || "chat");
  return { intent, items };
}


function extractGroqQuality(gd) {
  const segs = Array.isArray(gd?.segments) ? gd.segments : [];
  if (!segs.length) return { status: "usable", outcome: "ACCEPT", avg_logprob: null, no_speech_prob: null, compression_ratio: null, metadata_available: false };
  const avgLp = segs.reduce((s, x) => s + (typeof x.avg_logprob === "number" ? x.avg_logprob : -0.3), 0) / segs.length;
  const maxNsp = Math.max(...segs.map(x => (typeof x.no_speech_prob === "number" ? x.no_speech_prob : 0)));
  const maxCr = Math.max(...segs.map(x => (typeof x.compression_ratio === "number" ? x.compression_ratio : 1)));
  const txtLen = String(gd?.text || "").trim().length;
  let outcome = "ACCEPT";
  let status = "usable";
  if (txtLen === 0 || (maxNsp > 0.82 && avgLp < -0.90 && txtLen < 6)) { outcome = "REJECT_OR_REPROMPT"; status = "noise_rejected"; }
  else if (avgLp < -0.75 || maxCr > 2.4) { outcome = "REPAIR_CANDIDATE"; status = "low_confidence"; }
  return {
    status,
    outcome,
    avg_logprob: Number(avgLp.toFixed(3)),
    no_speech_prob: Number(maxNsp.toFixed(3)),
    compression_ratio: Number(maxCr.toFixed(2)),
    metadata_available: true
  };
}

function formatTtsTamil(text) {
  return String(text || "")
    .replace(/₹\s*([0-9,]+(?:\.\d+)?)/g, "$1 ரூபாய்")
    .replace(/\bkm\b/gi, "கிலோமீட்டர்")
    .replace(/\bIST\b/g, "இந்திய நேரம்")
    .replace(/\bAPK\b/g, "ஏ.பி.கே")
    .replace(/\bUI\b/g, "யூ.ஐ");
}

async function runSttPipeline(env, audioBuf, audioBytes, contactsHintRaw) {
  const sttKeys = getGroqKeys(env);
  let raw = "";
  let provider = "none";
  let quality = { status: "unavailable", outcome: "ACCEPT", avg_logprob: null, no_speech_prob: null, compression_ratio: null, metadata_available: false };
  for (const k of sttKeys) {
    try {
      const gForm = new FormData();
      gForm.append("file", new Blob([audioBuf], { type: "audio/wav" }), "yuna_mic.wav");
      gForm.append("model", "whisper-large-v3");
      gForm.append("language", "ta");
      gForm.append("response_format", "verbose_json");
      gForm.append("temperature", "0");
      const ch = String(contactsHintRaw || "").trim().slice(0, 220);
      const bp = "WhatsApp, YouTube, GPay, Brightness, Mobile Data, WiFi, நாமக்கல், திருநெல்வேலி, ஓபன் பண்ணு, கால் பண்ணு, டார்ச் ஆன், லோடு கீதா.";
      gForm.append("prompt", ch ? (bp + " " + ch).slice(0, 420) : bp);
      const gr = await fetch("https://api.groq.com/openai/v1/audio/transcriptions", { method: "POST", headers: { "Authorization": "Bearer " + k }, body: gForm });
      const gd = await gr.json();
      if (gr.ok && gd?.text) { raw = String(gd.text).trim(); provider = "groq"; quality = extractGroqQuality(gd); break; }
    } catch (_) {}
  }
  if (!raw && env.AI) {
    try {
      const cfStt = await env.AI.run("@cf/openai/whisper-large-v3-turbo", {
        audio: uint8ToBase64Safe(audioBytes),
        language: "ta",
        vad_filter: true,
        hallucination_silence_threshold: 0.5
      });
      if (cfStt?.text) { raw = String(cfStt.text).trim(); provider = "cloudflare_fallback"; }
    } catch (_) {}
  }
  let cleanedRaw = raw;
  if (cleanedRaw && hasNonTamilIndicScript(cleanedRaw)) {
    const rep = await repairIndicScriptLeak(env, cleanedRaw);
    cleanedRaw = (rep && !hasNonTamilIndicScript(rep)) ? rep : "";
  }
  return { ok: Boolean(cleanedRaw), raw, normalized: normalizeTamilRuntimeText(cleanedRaw), provider, quality };
}

async function runNluPipeline(env, ctx, { prompt, rawPrompt, sessionId, contacts, skipTier0 }) {
  const q = String(prompt || "").trim();
  const sid = String(sessionId || "default").replace(/[^a-zA-Z0-9_-]/g, "").slice(0, 40) || "default";
  const t0 = skipTier0 ? null : matchTier0FastPath(q);
  if (t0) return { ...t0, transcript: { raw: rawPrompt || q, normalized: q }, interpretation: { intent: mapActionsToOntology(t0.actions, "os_action").intent, slots: {}, missing_slots: [], requires_clarification: false }, pending_intent: null, execution: { status: "proposed_for_android" } };

  // Check active pending intent in D1 for multi-turn slot resolution or cancellation
  if (env.DB && q.length < 40) {
    try {
      const row = await env.DB.prepare("SELECT id, intent, slots_json, missing_slots_json, expires_at FROM yuna_pending_intents WHERE sid = ? AND status = 'awaiting_slot' ORDER BY created_at DESC LIMIT 1").bind(sid).first();
      if (row && row.expires_at > Date.now()) {
        if (/^(?:வேண்டாம்|கேன்சல்|நிறுத்து|cancel|no)$/i.test(q)) {
          await env.DB.prepare("UPDATE yuna_pending_intents SET status = 'cancelled' WHERE id = ?").bind(row.id).run();
          return { reply: "சரி, அந்தச் செயலை ரத்து செய்துவிட்டேன்.", actions: [], pending_intent: { id: row.id, status: "cancelled" }, execution: { status: "not_executed" } };
        }
        if (row.intent === "contacts.call" && !/டார்ச்|வைஃபை|நேரம்|தேதி|லோடு|அலாரம்/.test(q)) {
          await env.DB.prepare("UPDATE yuna_pending_intents SET status = 'resolved' WHERE id = ?").bind(row.id).run();
          const targetName = q.replace(/(?:க்கு|உக்கு|ஐ)$/g, "").trim();
          return {
            reply: `${targetName}-க்கு கால் செய்யவா?`,
            actions: [{ action: "DIAL", target: targetName }],
            interpretation: { intent: "contacts.call", slots: { contact_id: targetName }, missing_slots: [], requires_clarification: false },
            pending_intent: { id: row.id, status: "resolved" },
            execution: { status: "proposed_for_android" }
          };
        }
      }
    } catch (_) {}
  }

  const msgs = [
    { role: "system", content: "You are YUNA, Kannan's Executive PA in Tamil Nadu. Output strict JSON: {\"intent\":\"os_action|query|chat\",\"canonical_intent\":\"string\",\"slots\":{},\"missing_slots\":[],\"requires_clarification\":false,\"reply\":\"...\",\"actions\":[{\"action\":\"ACTION_NAME\",\"target\":\"...\"}]}. Reply in proper Tamil script." },
    { role: "user", content: q }
  ];
  let rawText = await askGroq(env, msgs);
  if (!rawText && env.NVIDIA_API_KEY) rawText = await askNvidiaNim(env.NVIDIA_API_KEY, msgs, 700);
  const cleaned = String(rawText || "{}").replace(/^```json\s*/i, "").replace(/```$/i, "").trim();
  let parsed = {};
  try { parsed = JSON.parse(cleaned); } catch (_) {}
  const actions = Array.isArray(parsed.actions) ? parsed.actions.slice(0, 4).filter(a => a && a.action && a.action !== "NONE") : [];
  return {
    reply: parsed.reply || "புரியவில்லை, மீண்டும் சொல்லுங்கள்.",
    actions,
    interpretation: { intent: mapActionsToOntology(actions, parsed.canonical_intent || "chat").intent, slots: parsed.slots || {}, missing_slots: parsed.missing_slots || [], requires_clarification: Boolean(parsed.requires_clarification) },
    pending_intent: null,
    execution: { status: actions.length ? "proposed_for_android" : "not_executed" }
  };
}


function hasNonTamilIndicScript(s) {
  return /[\u0900-\u097F\u0C00-\u0C7F\u0C80-\u0CFF\u0D00-\u0D7F]/.test(String(s || ""));
}

async function repairIndicScriptLeak(env, rawText) {
  try {
    const msgs = [
      { role: "system", content: "Whisper STT misheard Tamil/Tanglish/English speech as Hindi, Malayalam, Telugu, or Kannada script. Convert the phonetic sounds back into natural Tamil script or English words ONLY. Output ONLY the repaired text, nothing else." },
      { role: "user", content: String(rawText || "").slice(0, 300) }
    ];
    const out = await askGroq(env, msgs);
    return String(out || "").trim();
  } catch (_) { return ""; }
}
