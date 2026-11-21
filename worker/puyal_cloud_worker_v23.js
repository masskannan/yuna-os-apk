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
        worker: "puyal-v23-stt-fixed",
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
        const sttKeys = getGroqKeys(env);
        const form = await request.formData();
        const file = form.get("file");
        if (!file) return new Response(JSON.stringify({ ok: false, text: "", error: "No audio file" }), { status: 400, headers: cors });

        const audioBuf = await file.arrayBuffer();
        const audioBytes = new Uint8Array(audioBuf);
        const origName = String(file.name || "yuna_mic.wav").toLowerCase();
        const isMp3 = origName.endsWith(".mp3") || (audioBytes[0] === 0x49 && audioBytes[1] === 0x44 && audioBytes[2] === 0x33) || (audioBytes[0] === 0xFF && (audioBytes[1] & 0xE0) === 0xE0);
        const isWav = origName.endsWith(".wav") || (audioBytes[0] === 0x52 && audioBytes[1] === 0x49 && audioBytes[2] === 0x46 && audioBytes[3] === 0x46);
        const upName = isMp3 ? "yuna_mic.mp3" : (isWav ? "yuna_mic.wav" : "yuna_mic.m4a");
        const upMime = isMp3 ? "audio/mpeg" : (isWav ? "audio/wav" : "audio/mp4");

        let rawStt = "";
        let lastSttErr = "";
        for (const k of sttKeys) {
          try {
            const gForm = new FormData();
            gForm.append("file", new Blob([audioBuf], { type: upMime }), upName);
            gForm.append("model", "whisper-large-v3");
            gForm.append("response_format", "json");
            gForm.append("temperature", "0");
            gForm.append("prompt", "WhatsApp ஓபன் பண்ணு, YouTube போடு, GPay, PhonePe, Maps, பாலாவுக்கு கால் பண்ணு, ஸ்பீக்கர் ஆன், டார்ச் ஆன், ஆஃப், ஸ்கிரீன்ஷாட் எடு, பேக் போ, ஹோம் போ, ரீசன்ட் ஆப்ஸ், கிளியர் பண்ணு, நோட்டிபிகேஷன் பார், நெட் ஆன், வைஃபை, ஹாட்ஸ்பாட், ஃபிளைட் மோட், புளூடூத், சைலன்ட் மோட், வால்யூம் கூட்டு, பிரைட்னஸ், அலாரம் வை, டைமர், நாமக்கல் லோடு, நோட், பில் படி.");
            const gr = await fetch("https://api.groq.com/openai/v1/audio/transcriptions", {
              method: "POST",
              headers: { "Authorization": "Bearer " + k },
              body: gForm
            });
            const gd = await gr.json();
            if (gr.ok && gd?.text) { rawStt = String(gd.text).trim(); break; }
            lastSttErr = gd?.error?.message || ("HTTP " + gr.status);
          } catch (se) { lastSttErr = se.message; }
        }
        if (!rawStt && env.AI) {
          try {
            // reuse audioBytes from above
            const cfStt = await env.AI.run("@cf/openai/whisper-large-v3-turbo", {
              audio: uint8ToBase64Safe(audioBytes),
              initial_prompt: "WhatsApp ஓபன் பண்ணு, YouTube போடு, பாலாவுக்கு கால் பண்ணு, டார்ச் ஆன்"
            });
            if (cfStt?.text) rawStt = String(cfStt.text).trim();
          } catch (cfe) { lastSttErr = lastSttErr || cfe.message; }
        }
        if (!rawStt) return new Response(JSON.stringify({ ok: false, text: "", error: lastSttErr || "STT error" }), { headers: cors });
        const normStt = normalizeTamilRuntimeText(rawStt);
        return new Response(JSON.stringify({ ok: true, text: normStt }), { headers: cors });
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

      const { prompt = "", sessionId = "default", contacts = [] } = await request.json();
      const q = normalizeTamilRuntimeText(String(prompt).trim());
      const tier0 = matchTier0FastPath(q);
      if (tier0) {
        return new Response(JSON.stringify(tier0), { headers: cors });
      }
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
        "Allowed actions: OPEN_APP_DYNAMIC, GLOBAL_BACK, GLOBAL_HOME, GLOBAL_RECENTS, CLEAR_ALL_APPS, OPEN_NOTIFICATIONS, OPEN_QUICK_SETTINGS, LOCK_SCREEN, SCREENSHOT, SCROLL_DOWN, SCROLL_UP, TORCH_ON, TORCH_OFF, MOBILE_DATA_ON, MOBILE_DATA_OFF, WIFI_ON, WIFI_OFF, HOTSPOT_ON, HOTSPOT_OFF, BLUETOOTH_ON, BLUETOOTH_OFF, VOLUME_UP, VOLUME_DOWN, VOLUME_MAX, RINGER_SILENT, RINGER_VIBRATE, RINGER_NORMAL, BRIGHTNESS_UP, BRIGHTNESS_DOWN, DIAL, SPEAKER_ON, SPEAKER_OFF, WHO_IS_CALLING, GET_BATTERY_TIME, OPEN_MAPS_NAV, SET_ALARM, SET_TIMER, NOTE, LOADS, VISION.",
        "SAFETY RULE: For GPay, PhonePe, Paytm, ONLY output OPEN_APP_DYNAMIC (never send money).",
        "Tamil/Tanglish Examples: 'வாட்ஸ் ஆப் ஓபன் பண்ணு'->{\"reply\":\"வாட்ஸ்அப் ஓபன் பண்றேன்.\",\"actions\":[{\"action\":\"OPEN_APP_DYNAMIC\",\"target\":\"WhatsApp\"}]} | 'GPay-ல 500 அனுப்பு'->{\"reply\":\"பாதுகாப்புக்காக ஜிபே ஆப்பை மட்டும் ஓபன் பண்றேன்.\",\"actions\":[{\"action\":\"OPEN_APP_DYNAMIC\",\"target\":\"GPay\"}]} | 'இருட்டா இருக்கு டார்ச் ஆன் பண்ணு'->{\"reply\":\"டார்ச் ஆன் பண்ணிட்டேன்.\",\"actions\":[{\"action\":\"TORCH_ON\",\"target\":\"\"}]} | 'பாலாவுக்கு கால் பண்ணு ஸ்பீக்கர் ஆன்'->{\"reply\":\"பாலாவுக்கு ஸ்பீக்கர் ஆன் பண்ணி கால் பண்றேன்.\",\"actions\":[{\"action\":\"DIAL\",\"target\":\"Bala\"},{\"action\":\"SPEAKER_ON\",\"target\":\"\"}]} | 'ஸ்கிரீன்ஷாட் எடுத்துட்டு பேக் போ'->{\"reply\":\"ஸ்கிரீன்ஷாட் எடுத்துட்டு பேக் வந்துட்டேன்.\",\"actions\":[{\"action\":\"SCREENSHOT\",\"target\":\"\"},{\"action\":\"GLOBAL_BACK\",\"target\":\"\"}]} | 'நாமக்கல் டு சென்னை லோடு பாரு'->{\"reply\":\"நாமக்கல் - சென்னை லோடு விவரங்களைச் செக் பண்றேன்.\",\"actions\":[{\"action\":\"LOADS\",\"target\":\"Namakkal to Chennai\"}]} | 'டீசல் 5000 நோட் பண்ணு'->{\"reply\":\"டீசல் ₹5000 நோட்ஸ்ல சேவ் பண்ணிட்டேன்.\",\"actions\":[{\"action\":\"NOTE\",\"target\":\"டீசல் ₹5000\"}]} | 'காலை அஞ்சரை மணிக்கு அலாரம் வை'->{\"reply\":\"காலை 5:30 மணிக்கு அலாரம் செட் பண்ணிட்டேன்.\",\"actions\":[{\"action\":\"SET_ALARM\",\"target\":\"05:30\"}]} | 'திருநெல்வேலி வழி காட்டு'->{\"reply\":\"திருநெல்வேலிக்கு கூகுள் மேப்ஸ்ல வழி காட்டுறேன்.\",\"actions\":[{\"action\":\"OPEN_MAPS_NAV\",\"target\":\"Tirunelveli\"}]} | 'சவுண்ட் கேக்கல ஃபுல் வை'->{\"reply\":\"வால்யூம் ஃபுல் வச்சிட்டேன்.\",\"actions\":[{\"action\":\"VOLUME_MAX\",\"target\":\"\"}]}",
        "Max 4 actions. Never include markdown fences around JSON."
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
      if (isDeepTask) {
        rawText = await askNvidiaNim(env.NVIDIA_API_KEY, messages, 900);
        if (!rawText && getGroqKeys(env).length) rawText = await askGroq(env, messages);
      } else {
        if (getGroqKeys(env).length) rawText = await askGroq(env, messages);
        if (!rawText && env.NVIDIA_API_KEY) rawText = await askNvidiaNim(env.NVIDIA_API_KEY, messages, 700);
      }
      if (!rawText && env.AI) {
        try {
          const cfRes = await env.AI.run("@cf/meta/llama-3.3-70b-instruct-fp8-fast", { messages, max_tokens: 900 });
          rawText = cfRes?.response || "";
        } catch (_) {}
      }
      if (!rawText) return new Response(JSON.stringify({ reply: "Cloud AI தயாராக இல்லை.", actions: [] }), { headers: cors });

      const rawStr = (typeof rawText === "string") ? rawText : JSON.stringify(rawText);
      const cleaned = rawStr.replace(/^```json\s*/i, "").replace(/```$/i, "").trim();
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

function getGroqKeys(env) {
  const pool = String(env.GROQ_API_KEYS || "").split(",").map(s => s.trim()).filter(Boolean);
  if (env.GROQ_API_KEY && !pool.includes(env.GROQ_API_KEY)) pool.unshift(env.GROQ_API_KEY);
  return pool;
}

async function askGroq(envOrKey, messages) {
  const keys = (typeof envOrKey === "string") ? [envOrKey] : getGroqKeys(envOrKey);
  for (const key of keys) {
    try {
      const r = await fetch("https://api.groq.com/openai/v1/chat/completions", {
        method: "POST",
        headers: { "Authorization": "Bearer " + key, "Content-Type": "application/json" },
        body: JSON.stringify({ model: "llama-3.3-70b-versatile", temperature: 0.25, max_tokens: 650, messages })
      });
      if (r.status === 429 || r.status >= 500) continue;
      const d = await r.json();
      const txt = d?.choices?.[0]?.message?.content || "";
      if (txt) return txt;
    } catch (_) {}
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
  } catch (_) { return ""; }
}


const PHONETIC_STT_RULES = [
  [/(?:^|\s)(?:ஏல|மச்சி|மச்சான்|நைனா|அப்பு|மாப்பிள்ளை|அண்ணாச்சி|ஏங்க|ப்ரோ|டா|டி|பா|bro|machi|machan)(?=\s|$)/gi, " "],
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
  [/வைபை|வை\s*பை|வை\s*ஃபை/g, "வைஃபை"],
  [/ஹாட்\s*ஸ்பாட்|ஹாட்ஸ்பொட்/g, "ஹாட்ஸ்பாட்"],
  [/பிளைட்\s*மோட்|ஃப்ளைட்\s*மோட்|ஏரோபிளேன்\s*மோட்/g, "ஃபிளைட் மோட்"],
  [/ப்ளூடூத்|புளூ\s*டூத்/g, "புளூடூத்"],
  [/நோட்டிஃபிகேஷன்|நோட்டிபிகேஷன்|நோடிபிகேஷன்/g, "நோட்டிபிகேஷன்"],
  [/ப்ரைட்னஸ்|பிரைட்\s*னஸ்/g, "பிரைட்னஸ்"],
  [/சைலன்ட்|சைலென்ட்/g, "சைலன்ட்"],
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


async function askNvidiaNim(rawKey, messages, maxTokens = 700) {
  const key = String(rawKey || "").trim().replace(/^Nvapi-/, "nvapi-");
  if (!key) return "";
  try {
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), 11000);
    const r = await fetch("https://integrate.api.nvidia.com/v1/chat/completions", {
      method: "POST",
      signal: ctrl.signal,
      headers: { "Authorization": "Bearer " + key, "Content-Type": "application/json" },
      body: JSON.stringify({ model: "meta/llama-3.3-70b-instruct", temperature: 0.25, max_tokens: maxTokens, messages })
    });
    clearTimeout(t);
    if (!r.ok) return "";
    const d = await r.json();
    return d?.choices?.[0]?.message?.content || "";
  } catch (_) { return ""; }
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
