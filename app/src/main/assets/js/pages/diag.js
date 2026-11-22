'use strict';
/* Batch 7: 28 system diagnostics. Verifies the bridge contract (37 calls, 10 callbacks, storage keys). */
const BRIDGE=['executeAction','startCloudWhisper','openAppByName','stopCloudWhisper','speakSmartWithVoice','speakSmart','speakPersona','stopVoice','startVoice','scanScreen','saveObsidianNote','readClipboard','getSyncedContactsJson','renderPdfPagesBase64','getDetailedPermissions','verifyOwnerBiometric','stopTts','stopSpeaking','stopLiveSession','startLiveSession','startFloatingOverlay','startBluetoothMic','setLiveMuted','replyLastWa','openPermissionSettings','lookupContact','isExternalCallActive','getWaSummary','getPermissionStatus','getInstalledAppsList','getCrashLog','finishVoiceCapture','enqueueCloudTask','clickOnScreen','checkSensitive','cancelCloudWhisper','bargeInLive'];
const CBS=['onYunaAppResumed','onYunaMicAborted','onYunaMicLevel','onYunaProcessText','onYunaTtsDone','onYunaVoiceEnded','onYunaVoicePartial','onYunaVoiceResult','onYunaWhisperChunk'];
const KEYS=['yuna_lang','yuna_ui_lang','yuna_ta_voice','yuna_voice_persona','yuna_glass_idx','yuna_brain','yuna_safety_mode','yuna_cloud_url','yuna_sessions','yuna_cloud_sid','yuna_trusted_calls','yuna_notes_v1','yuna_lang_label','yuna_mood','yuna_ui_ver'];
const NEWKEYS=['yuna_v4_state','yuna_float_on'];
Pg.def('diag',()=>T('கணினி சோதனை','Diagnostics'),p=>{
 const b=B(),miss=BRIDGE.filter(n=>!b||typeof b[n]!=='function'),cb=CBS.filter(n=>typeof window[n]!=='function');
 UI.sec(p,'YunaBridge');UI.row(p,T('இணைப்பு','Connected'),UI.pill(b?'ON':T('இல்லை (உலாவி)','No (browser)')));
 UI.row(p,T('Bridge அழைப்புகள்','Bridge calls'),UI.pill(BRIDGE.length-miss.length+' / '+BRIDGE.length),miss.length&&b?T('இல்லாதவை: ','Missing: ')+miss.join(', '):'');
 UI.row(p,'Callbacks',UI.pill((CBS.length-cb.length)+' / '+CBS.length),cb.join(', '));
 UI.sec(p,T('திரை','Viewport'));const vv=window.visualViewport;
 UI.row(p,'--kb',UI.pill(getComputedStyle(document.documentElement).getPropertyValue('--kb').trim()||'0px'));UI.row(p,T('தெரியும் உயரம்','Visible height'),UI.pill(Math.round(vv?vv.height:innerHeight)+'px'));UI.row(p,'--dock',UI.pill(getComputedStyle(document.documentElement).getPropertyValue('--dock').trim()));
 UI.sec(p,'localStorage');UI.row(p,T('அங்கீகரிக்கப்பட்ட keys','Known keys'),UI.pill(KEYS.filter(k=>ls.g(k)!=null).length+' / '+KEYS.length));
 UI.row(p,T('புதிய keys (உங்கள் ஒப்புதல்)','New keys (need your approval)'),UI.pill(NEWKEYS.filter(k=>ls.g(k)!=null).length+' / '+NEWKEYS.length),NEWKEYS.join(', '));
 UI.row(p,T('உரையாடல்கள்','Sessions'),UI.pill(String(sessions.length)));
 UI.sec(p,'Crash log');const o=el('pre','out','—');p.appendChild(o);
 UI.btn(p,T('Crash log காட்டு','Show crash log'),()=>{const r=call('getCrashLog');o.textContent=r?String(r).slice(-4000):T('பதிவு இல்லை / APK-ல் மட்டும்','No log / APK only')});
 UI.btn(p,T('நகலெடு','Copy'),()=>copyText(o.textContent))});
