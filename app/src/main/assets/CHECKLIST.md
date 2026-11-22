# YUNA — பட்டன் & கருவி checklist (r3)
குறிப்பு: Claude / ChatGPT / Gemini பட்டியல் என் பொது அறிவிலிருந்து (நேரடியாகச் சரிபார்க்கவில்லை). YUNA நிலை என் குறியீட்டிலிருந்து. ✅ உள்ளது · 🟡 பாதி/dummy · ❌ இல்லை

## 1. Composer
| அம்சம் | நிலை | குறிப்பு |
|---|---|---|
| அனுப்பு / நிறுத்து (■) | ✅ | |
| + இணைப்பு (படம், கேமரா, குரல், கோப்பு) | ✅ | அதிகபட்சம் 4; PDF ≤8 பக்கம் |
| மைக் / Live | ✅ | |
| மாதிரி தேர்வு (model picker) | ❌ | Cloud Worker routing தேவை |
| Composer-ல் கருவி மெனு (web, think, research) | 🟡 | கருவிகள் மையத்தில் மட்டும் |
| தற்காலிக (incognito) chat | ❌ | |
| தொனி/பாணி தேர்வு | ❌ | |
| Draft நினைவு | ❌ | (key விதி காரணமாக வேண்டுமென்றே இல்லை) |
## 2. செய்தி செயல்கள்
| நகல் ✅ | மீண்டும் உருவாக்கு ✅ (இடத்திலேயே மாற்றும்) | பகிர் ✅ |
| 👍👎 ✅ (r4, சாதனத்தில் மட்டும் சேமிப்பு) | பயனர் செய்தி திருத்து ❌ | ஒரு செய்தியை வாசி (🔊) ✅ (r4, மீண்டும் தட்டினால் நிறுத்தும்) |
| மேற்கோள் (quote) ❌ (பழைய app-ல் இருந்தது) | கிளை/பதிப்பு (1/2) ❌ | உரை தேர்வு ❌ |
## 3. உரையாடல் நிர்வாகம்
| புதிய ✅ | வரலாறு+தேடல்+தேதிக் குழு ✅ | நீக்கு+Undo ✅ | பகிர் ✅ | குறிப்பாக ஏற்று ✅ |
| மறுபெயர் ✅ | Pin ✅ | chat-க்குள் தேடல் ✅ (r4) | Archive ❌ | எல்லாம் அழி ❌ | PDF ஏற்றுமதி ❌ |
## 4. வெளியீடு வகைகள்
| வகை | நிலை |
|---|---|
| உரை/markdown/பட்டியல்/table/code+நகல் | ✅ |
| படம், வீடியோ, கோப்பு அட்டை, HTML முன்னோட்டம் | ✅ (r3) — Cloud `outputs[]` அனுப்ப வேண்டும் |
| கணிதம் (LaTeX) ❌ | வரைபடம்/chart ❌ | மூல (citation) chips ❌ | Streaming ❌ | Artifacts gallery 🟡 dummy |
## 5. கருவிகள்
✅ web.search, phone.actions, wa.intel, notes.export (toggle) · files.pdf, camera, screen.vision (launch)
🟡 dummy: படம், வீடியோ, Canvas&Code, Deep research, Gmail/Calendar, Browser agent, MCP, API, RAG
❌ Study mode, Scheduled tasks, Custom agents, Code Run, Voice-print enrol
## 6. அமைப்புகள்
✅ மொழி, குரல், தமிழ் குரல், கண்ணாடி அடர்த்தி, Cloud URL, safety mode, கைரேகை, அனுமதிகள், நினைவு நீக்கு, diagnostics
❌ Custom instructions, நினைவு சேர், இருள்/ஒளி கைமுறைத் தேர்வு, அறிவிப்புகள், பயன்பாடு/limit, தரவு ஏற்றுமதி

## 7. வெளியீட்டு வடிவம் & தர்க்கம் (Cloud → app)
Cloud பதில்: `{reply, actions[], outputs[]}`. `outputs[]` உருப்படி: `{type,name,mime,url|b64,poster,size,lang,text,status,progress,message}`
| வகை | வடிவம் | எங்கே | எப்படி இயங்கும் | தோல்வி |
|---|---|---|---|---|
| படம் | PNG/WebP/JPEG, https URL (b64 சிறியவைக்கு மட்டும்) | YUNA தாளில் உரைக்குக் கீழ் | உருவாகும்போது shimmer + `status:'working'`; தட்டினால் முழுத்திரை + சேமி | மறுப்பு/நேரம் → rose அட்டை + மீண்டும் |
| வீடியோ | MP4 (H.264) ≤25MB, poster | அதே | `progress` %; முடிந்ததும் `<video controls>` | இயக்க முடியாது செய்தி |
| PDF/Word/Excel/PPT | https URL, `name`, `size` | கோப்பு அட்டை | திற / சேமி | ஆதரவு இல்லா வடிவம் செய்தி |
| HTML | `text` = முழு HTML | முன்னோட்டம்⇄Code, முழுத்திரை | `sandbox=""` (script இல்லை); "இயக்கு" உறுதிக்குப் பின் `allow-scripts` | |
| Code | `lang`+`text` | code தாள்+நகல் | | |
**பட வழங்குநர்:** Claude படத்தை (raster) உருவாக்காது; SVG/HTML மட்டும். படங்களுக்கு ChatGPT (gpt-image) அல்லது Gemini (Imagen) — தேர்வு Worker-ல்; Worker முடிவை R2/Storage-ல் வைத்து https URL அனுப்ப வேண்டும்.
**எச்சரிக்கை:** வரலாற்றில் `b64` சேமிக்கப்படாது (localStorage நிரம்பும்); URL இல்லாத படம் மீள்ஏற்றத்தில் மறையும். ⇒ Worker URL தர வேண்டும்.
**VERIFY (APK):** `<a download>` WebView-ல் சேமிக்குமா? இல்லையெனில் Kotlin DownloadListener/bridge `saveFile` தேவை (Kotlin தொடக்கூடாது என்ற விதி மீறப்படும் — உங்கள் முடிவு).
## 8. எதிர்கால pipeline (நீங்கள் பேசுவது)
Tools hub toggle → `tools:["img"]` → Worker router → provider → Storage URL → `outputs[]` → `Out.render`. படிகள்: 1) router 2) Storage 3) `status:'working'` polling (`/job/{id}`) 4) Artifacts gallery சேமிப்பு.
