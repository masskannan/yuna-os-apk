export default {
  async fetch(request, env) {
    const cors = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type, Authorization",
      "Content-Type": "application/json; charset=utf-8"
    };
    if (request.method === "OPTIONS") return new Response(null, { headers: cors });

    const u0 = new URL(request.url);
    if (u0.pathname === "/health" && request.method === "GET") {
      return new Response(JSON.stringify({ ok: true, worker: "puyal-v12", ts: Date.now() }), { headers: cors });
    }

    if (u0.pathname === "/vision" && request.method === "POST") {
      try {
        const body = await request.json();
        const prompt = String(body.prompt || "இந்தப் படம் / திரையைத் தமிழில் சுருக்கமாக விளக்குக.").slice(0, 600);
        const imgs = Array.isArray(body.images) ? body.images.slice(0, 5) : (body.imageBase64 ? [{ base64: body.imageBase64, mimeType: body.mimeType || "image/jpeg" }] : []);
        if (!imgs.length) return new Response(JSON.stringify({ ok: false, reply: "படம் இல்லை." }), { status: 400, headers: cors });
        if (!env.GROQ_API_KEY) return new Response(JSON.stringify({ ok: false, reply: "GROQ_API_KEY இல்லை." }), { status: 500, headers: cors });

        const content = [{ type: "text", text: prompt + " (தமிழில் சுருக்கமாக, தெளிவாகப் பதிலளி.)" }];
        for (const im of imgs) {
          const b64 = String(im.base64 || "").replace(/^data:[^;]+;base64,/, "");
          if (b64) content.push({ type: "image_url", image_url: { url: `data:${im.mimeType || "image/jpeg"};base64,${b64}` } });
        }
        const vr = await fetch("https://api.groq.com/openai/v1/chat/completions", {
          method: "POST",
          headers: { "Authorization": "Bearer " + env.GROQ_API_KEY, "Content-Type": "application/json" },
          body: JSON.stringify({ model: "meta-llama/llama-4-scout-17b-16e-instruct", temperature: 0.2, max_tokens: 700, messages: [{ role: "user", content }] })
        });
        const vd = await vr.json();
        const vReply = vd?.choices?.[0]?.message?.content || "படத்தைப் படிக்க முடியவில்லை.";
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
        const clean = String(text).replace(/[*_`~#⚡•●▶⏸➤]/g, " ").replace(/\s+/g, " ").trim().slice(0, 500);
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
        if (!env.GROQ_API_KEY) return new Response(JSON.stringify({ ok: false, text: "", error: "GROQ_API_KEY missing" }), { headers: cors });
        const form = await request.formData();
        const file = form.get("file");
        if (!file) return new Response(JSON.stringify({ ok: false, text: "", error: "No audio file" }), { status: 400, headers: cors });

        const gForm = new FormData();
        gForm.append("file", file, "yuna_mic.m4a");
        gForm.append("model", "whisper-large-v3");
        gForm.append("response_format", "json");
        gForm.append("prompt", "Tamil, English, and Tanglish mobile assistant commands like YouTube, WhatsApp, Torch, Speaker, Settings.");

        const gr = await fetch("https://api.groq.com/openai/v1/audio/transcriptions", {
          method: "POST",
          headers: { "Authorization": "Bearer " + env.GROQ_API_KEY },
          body: gForm
        });
        const gd = await gr.json();
        return new Response(JSON.stringify({ ok: true, text: (gd?.text || "").trim() }), { headers: cors });
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

      if (request.method !== "POST") return new Response(JSON.stringify({ error: "POST only" }), { status: 405, headers: cors });

      const { prompt = "", sessionId = "default" } = await request.json();
      const q = String(prompt).trim();
      const sid = String(sessionId).replace(/[^a-zA-Z0-9_-]/g, "").slice(0, 40) || "default";
      if (!q) return new Response(JSON.stringify({ reply: "சொல்லுங்கள் கண்ணன், என்ன உதவி வேண்டும்?", actions: [] }), { headers: cors });

      let historyMsgs = [];
      if (env.DB) {
        try {
          await env.DB.prepare("CREATE TABLE IF NOT EXISTS yuna_memory (id INTEGER PRIMARY KEY AUTOINCREMENT, sid TEXT, role TEXT, content TEXT, ts INTEGER)").run();
          const rows = await env.DB.prepare("SELECT role, content FROM yuna_memory WHERE sid = ? ORDER BY id DESC LIMIT 6").bind(sid).all();
          if (rows?.results?.length) historyMsgs = rows.results.reverse().map(r => ({ role: r.role, content: r.content }));
        } catch (_) {}
      }

      // Personalized YUNA System Prompt: MD's Rugged PhD-Level Executive PA / Chief of Staff
      const sys = [
        "You are YUNA, Kannan's fiercely loyal Executive PA / Chief of Staff (as if reporting directly to an MD) from Tamil Nadu, India.",
        "Role & Hierarchy: You sit at the top protecting Kannan's time, focus, and phone OS. Specialized domain agents (like Live Loads for freight) handle operational freight details underneath; you focus on executive decisions, traps, schedule, and OS control.",
        "Personality: Rugged, calm under pressure, PhD-level logical strategist. Think 2-3 moves ahead: spot hidden traps, predict what the other party will logically ask or exploit next, and add a crisp 1-line second-order warning when a decision carries risk.",
        "Honesty & Spine: Zero sugarcoating, zero blind yes-man behavior, never fake success. If Kannan's move has a logical flaw, push back directly with ironclad reasoning — stern when warranted, always backed by logic. Under urgency, give 1 sharp diagnosis + 1 decisive action.",
        "Language Rule: When Kannan writes in Tamil or Tanglish, reply in clean, proper Tamil script (தமிழ்) — NEVER use Tanglish transliteration. If he writes purely in English, reply in clean English. Keep replies tight, executive, and high-signal (1-3 sentences unless deep analysis is requested).",
        "If Kannan asks for 1 or more phone actions, return strict JSON: {\"reply\":\"...\",\"actions\":[{\"action\":\"ACTION_NAME\",\"target\":\"...\"}]}.",
        "Allowed actions: TORCH_ON, TORCH_OFF, OPEN_APP_DYNAMIC, GLOBAL_BACK, GLOBAL_HOME, SCREENSHOT, GET_BATTERY_TIME, OPEN_MAPS_NAV, SET_ALARM, SET_TIMER, DIAL, SPEAKER_ON, SPEAKER_OFF, WHO_IS_CALLING.",
        "Max 4 actions. Never include markdown fences around JSON."
      ].join(" ");

      const contactHint = Array.isArray(contacts) && contacts.length
        ? " Known Contacts (match Tamil/phonetic names accurately to exact name for DIAL): " + contacts.slice(0, 60).join(", ") + "."
        : "";
      const messages = [{ role: "system", content: sys + contactHint }, ...historyMsgs, { role: "user", content: q }];
      let rawText = "";
      if (env.GROQ_API_KEY) rawText = await askGroq(env.GROQ_API_KEY, messages);
      if (!rawText && env.AI) {
        try {
          const cfRes = await env.AI.run("@cf/meta/llama-3.3-70b-instruct-fp8-fast", { messages, max_tokens: 900 });
          rawText = cfRes?.response || "";
        } catch (_) {}
      }
      if (!rawText) return new Response(JSON.stringify({ reply: "Cloud AI தயாராக இல்லை.", actions: [] }), { headers: cors });

      const cleaned = rawText.replace(/^```json\s*/i, "").replace(/```$/i, "").trim();
      const jStart = cleaned.indexOf("{"), jEnd = cleaned.lastIndexOf("}");
      const jsonSlice = (jStart !== -1 && jEnd > jStart) ? cleaned.slice(jStart, jEnd + 1) : cleaned;
      let reply = cleaned;
      let actions = [];
      try {
        const parsed = JSON.parse(jsonSlice);
        reply = parsed.reply || cleaned;
        if (Array.isArray(parsed.actions)) actions = parsed.actions.slice(0, 4).filter(a => a && a.action && a.action !== "NONE");
        else if (parsed.action && parsed.action !== "NONE") actions = [{ action: parsed.action, target: parsed.target || "" }];
      } catch (_) {
        const m = cleaned.match(/"reply"\s*:\s*"([^"]+)/);
        if (m && m[1]) reply = m[1];
      }

      if (env.DB) {
        try {
          const now = Date.now();
          await env.DB.prepare("INSERT INTO yuna_memory (sid, role, content, ts) VALUES (?, 'user', ?, ?)").bind(sid, q.slice(0, 500), now).run();
          await env.DB.prepare("INSERT INTO yuna_memory (sid, role, content, ts) VALUES (?, 'assistant', ?, ?)").bind(sid, reply.slice(0, 500), now + 1).run();
          await env.DB.prepare("DELETE FROM yuna_memory WHERE sid = ? AND id NOT IN (SELECT id FROM yuna_memory WHERE sid = ? ORDER BY id DESC LIMIT 40)").bind(sid, sid).run();
        } catch (_) {}
      }

      return new Response(JSON.stringify({ reply, actions }), { headers: cors });
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

async function askGroq(key, messages) {
  try {
    const r = await fetch("https://api.groq.com/openai/v1/chat/completions", {
      method: "POST",
      headers: { "Authorization": "Bearer " + key, "Content-Type": "application/json" },
      body: JSON.stringify({ model: "llama-3.3-70b-versatile", temperature: 0.3, max_tokens: 900, messages })
    });
    const d = await r.json();
    return d?.choices?.[0]?.message?.content || "";
  } catch (_) { return ""; }
}
