'use strict';
/* Batch 3-4: 09 floating ball, 10 orb states, 11 vision, 12 camera, 13 settings, 14 appearance, 15 voice, 16 AI models */
const TA_VOICES=[['ta-SG-VenbaNeural','வெண்பா'],['ta-IN-PallaviNeural','பல்லவி'],['ta-IN-ValluvarNeural','வள்ளுவர்'],['ta-SG-AnbuNeural','அன்பு'],['ta-MY-KaniNeural','கனி'],['ta-MY-SuryaNeural','சூர்யா'],['ta-LK-SaranyaNeural','சரண்யா'],['ta-LK-KumarNeural','குமார்']];
const LANGS=[['ta-IN','TA','ta','தமிழ்'],['en-IN','EN','en','English'],['en-IN','TA+EN','ta','தமிழ் + English']];
const GLASS=[{a:.04,ta:'தெளிவு',en:'Clear'},{a:.07,ta:'சமநிலை',en:'Balanced'},{a:.16,ta:'அடர்',en:'Dense'}];
const langIdx=()=>{const l=ls.g('yuna_lang_label');const i=LANGS.findIndex(x=>x[1]===(l||(ls.g('yuna_lang')==='en-IN'?'EN':'TA')));return i<0?0:i};
const glassIdx=()=>Math.min(2,Math.max(0,Number(ls.g('yuna_glass_idx')||1)||0));
function applyGlass(){document.documentElement.style.setProperty('--glass-a',GLASS[glassIdx()].a)}
YUNA.register('glass',applyGlass);
function setLang(i){const x=LANGS[i];ls.s('yuna_lang',x[0]);ls.s('yuna_lang_label',x[1]);ls.s('yuna_ui_lang',x[2]);L=x[2];applyLang();Pg.refresh()}  /* persists across restart */
function permJson(){try{return JSON.parse(call('getDetailedPermissions')||'{}')||{}}catch(_){return{}}}

Pg.def('settings',()=>t('settings'),p=>{
 UI.sec(p,T('பொது','General'));
 UI.row(p,t('lang'),UI.pill(LANGS[langIdx()][3],()=>setLang((langIdx()+1)%3)));
 UI.link(p,T('தோற்றம்','Appearance'),'appearance');UI.link(p,T('குரல்','Voice'),'voice');UI.link(p,T('AI மாதிரி & Cloud','AI model & Cloud'),'models');
 UI.sec(p,T('பாதுகாப்பு','Security'));UI.link(p,t('perms'),'permissions');UI.link(p,T('பாதுகாப்பு & உரிமையாளர்','Security & owner'),'security');
 UI.sec(p,T('நினைவு & கருவிகள்','Memory & tools'));UI.link(p,t('memory'),'memory');UI.link(p,T('அறிவுத் தளம் (RAG)','Knowledge (RAG)'),'rag');UI.link(p,T('கருவிகள் மையம்','Tools hub'),'tools');
 UI.sec(p,T('திரை','Screen'));UI.link(p,t('float'),'float');UI.link(p,T('ஆர்ப் நிலைகள்','Orb states'),'orbstates');UI.link(p,T('திரைப் பார்வை','Screen vision'),'vision');UI.link(p,T('கேமரா','Camera'),'camera');
 UI.sec(p,T('டெவலப்பர்','Developer'));UI.link(p,T('கணினி சோதனை','Diagnostics'),'diag');
 UI.row(p,t('export'),UI.pill(T('குறிப்பாக','As note'),()=>{const s=cur&&cur.msgs.length?cur.msgs.map(m=>(m.r==='u'?'• ':'  ')+m.t).join('\n'):'';if(!s){toast(t('histEmpty'));return}call('saveObsidianNote',s);toast(t('copied'))}));
 UI.row(p,t('ver'),el('span','','UI v5 · r5'))});

Pg.def('appearance',()=>T('தோற்றம்','Appearance'),p=>{
 UI.sec(p,T('கண்ணாடி அடர்த்தி','Glass density'));const w=el('div','opt');
 GLASS.forEach((g,i)=>{const b=UI.pill(L==='ta'?g.ta:g.en,()=>{ls.s('yuna_glass_idx',String(i));applyGlass();$$('.opt .pill',p).forEach((x,j)=>x.classList.toggle('on',j===i))});b.classList.toggle('on',i===glassIdx());w.appendChild(b)});p.appendChild(w);
 UI.note(p,T('தீம்: சாதனத்தின் இருள்/ஒளி அமைப்பைப் பின்பற்றும்.','Theme follows your device light/dark setting.'))});

Pg.def('voice',()=>T('குரல்','Voice'),p=>{
 UI.sec(p,T('பேச்சு மொழி','Speech language'));UI.row(p,t('lang'),UI.pill(LANGS[langIdx()][3],()=>setLang((langIdx()+1)%3)));
 UI.sec(p,T('YUNA குரல்','YUNA voice'));const per=ls.g('yuna_voice_persona')||'YUNA',po=el('div','opt');
 [['YUNA','YUNA ♀'],['PUYAL','PUYAL ♂'],['SILENT',T('அமைதி','Silent')]].forEach(([k,n])=>{const b=UI.pill(n,()=>{ls.s('yuna_voice_persona',k);$$('.opt .pill',po).forEach(x=>x.classList.toggle('on',x===b))});b.classList.toggle('on',k===per);po.appendChild(b)});p.appendChild(po);
 UI.sec(p,T('தமிழ் குரல்','Tamil voice'));const cv=ls.g('yuna_ta_voice')||TA_VOICES[0][0],vo=el('div','opt');
 TA_VOICES.forEach(([k,n])=>{const b=UI.pill(n,()=>{ls.s('yuna_ta_voice',k);$$('.pill',vo).forEach(x=>x.classList.toggle('on',x===b));Yuna.tts(T('வணக்கம், நான் '+n,'Hello, this is '+n))});b.classList.toggle('on',k===cv);vo.appendChild(b)});p.appendChild(vo);
 UI.btn(p,T('குரலைச் சோதி','Test voice'),()=>Yuna.tts(T('வணக்கம், நான் யூனா.','Hello, I am YUNA.')))});

Pg.def('models',()=>T('AI மாதிரி & Cloud','AI model & Cloud'),p=>{
 UI.sec(p,T('மூளை','Brain'));const br=ls.g('yuna_brain')==='LOCAL'?'LOCAL':'CLOUD';
 UI.row(p,T('செயல்முறை','Mode'),UI.pill(br==='CLOUD'?'Cloud':'Local',b=>{const n=ls.g('yuna_brain')==='LOCAL'?'CLOUD':'LOCAL';ls.s('yuna_brain',n);b.textContent=n==='CLOUD'?'Cloud':'Local'}),T('இந்தப் பதிப்பில் எல்லாக் கோரிக்கைகளும் Cloud வழியாகவே செல்லும்','In this version every request still goes through Cloud'));
 UI.sec(p,'Cloud URL');const f=el('input','fld');f.type='url';f.placeholder='https://…workers.dev/chat';f.value=ls.g('yuna_cloud_url')||'';f.autocomplete='off';p.appendChild(f);
 const st=el('p','note','');p.appendChild(st);
 UI.btn(p,T('சோதித்துச் சேமி','Test & save'),()=>{const v=f.value.trim();
  if(!v){UI.ask(T('Cloud URL-ஐ நீக்கவா?','Remove the Cloud URL?'),()=>{try{localStorage.removeItem('yuna_cloud_url')}catch(_){}st.textContent=T('நீக்கப்பட்டது','Removed')});return}
  if(!/^https:\/\/[^\s]+$/i.test(v)){st.className='note bad';st.textContent=T('https:// உடன் தொடங்கும் முழு முகவரி தேவை','Needs a full https:// address');return}
  UI.ask(T('இந்த முகவரிக்கு உங்கள் உரையாடல்கள் அனுப்பப்படும். நம்பகமானதா?','Your chats will be sent to this address. Do you trust it?')+'\n'+v,async()=>{
   st.className='note';st.textContent=T('சோதிக்கிறேன்…','Testing…');const c=new AbortController(),tm=setTimeout(()=>c.abort(),6000);
   try{const u=new URL(v);const r=await fetch(u.origin+'/health',{signal:c.signal});clearTimeout(tm);ls.s('yuna_cloud_url',v);st.className='note '+(r.ok?'ok':'bad');st.textContent=r.ok?T('இணைந்தது · சேமிக்கப்பட்டது','Connected · saved'):T('சேமிக்கப்பட்டது; /health பதில் '+r.status,'Saved; /health returned '+r.status)}
   catch(_){clearTimeout(tm);ls.s('yuna_cloud_url',v);st.className='note bad';st.textContent=T('சேமிக்கப்பட்டது; ஆனால் சேவையை அடைய முடியவில்லை','Saved, but the service was not reachable')}},T('சேமி','Save'))})});

/* 09 floating ball (A8): new key yuna_float_on, needs overlay permission first */
Pg.def('float',()=>t('float'),p=>{
 const on=ls.g('yuna_float_on')==='1';
 UI.row(p,t('float'),UI.sw(on,(v,b)=>{if(v){let s={};try{s=JSON.parse(call('getPermissionStatus')||'{}')||{}}catch(_){}
   if(s.overlay===false||s.draw_over===false){b.classList.remove('on');toast(T('முதலில் "மேலே காட்டு" அனுமதி தேவை','Allow "display over other apps" first'),()=>call('openPermissionSettings'),T('திற','Open'));return}
   call('startFloatingOverlay');ls.s('yuna_float_on','1')}else ls.s('yuna_float_on','0')}),T('வேறு app மேல் YUNA-வைக் காட்டும்','Shows YUNA over other apps'));
 UI.sec(p,T('பந்தின் செயல்கள்','Ball gestures'));
 [[T('தட்டு','Tap'),T('விரி','Expand')],[T('பிடி','Hold'),T('YUNA திற','Open YUNA')],[T('ஓரத்தில்','Idle'),T('ஓரத்தில் ஒட்டும்','Snaps to edge')],[T('இழு','Drag'),T('மூடு','Dismiss')]].forEach(r=>UI.row(p,r[0],el('span','',r[1])))});

Pg.def('orbstates',()=>T('ஆர்ப் நிலைகள்','Orb states'),p=>{
 [['LISTEN',T('ripple வெளியே பரவும்','Ripples spread outward')],['THINK',T('வளையம் 10 வினாடிக்கு ஒரு சுற்று','Ring turns once per 10s')],['SEARCH',T('தேடல் உண்மையில் நடந்தால் மட்டும்','Only when a search really runs')],['READ',T('கோப்பு chip-ல் % ஏறும்','File chip shows progress %')],['WRITE',T('caret ஓடும்','Caret runs')],['SPEAK',T('உயிர்மெய் ஒளிரும்','Uyir-mei letters glow')],['MUTE',T('மங்கல் 40%; சுழற்சி நிற்கும்','Dimmed 40%; rotation stops')]]
  .forEach(([k,d])=>UI.row(p,t(k),null,d));UI.note(p,T('ஆர்ப் படம் மாறாது; வளையமும் ஒளியும் மட்டுமே மாறும்.','The orb image never changes; only ring and glow do.'))});

Pg.def('vision',()=>T('திரைப் பார்வை','Screen vision'),p=>{
 UI.note(p,T('தொலைபேசித் திரையின் எழுத்தைப் படித்து விளக்கும்.','Reads and explains the text on your phone screen.'));
 const out=el('pre','out',T('இன்னும் ஸ்கேன் செய்யவில்லை','Not scanned yet'));
 UI.btn(p,T('திரையை ஸ்கேன் செய்','Scan screen'),()=>{
  let s=permJson();if(B()&&s&&Object.keys(s).length&&Object.entries(s).some(([k,v])=>/access/i.test(k)&&!v)){out.textContent=T('Accessibility அனுமதி இல்லை. அனுமதிகள் திரையில் இயக்கவும்.','Accessibility is off. Enable it in Permissions.');return}
  const r=call('scanScreen');out.textContent=r?String(r).slice(0,6000):T('திரையில் எழுத்து கிடைக்கவில்லை','No text found on screen')});
 UI.btn(p,T('YUNA-விடம் கேள்','Ask YUNA about it'),()=>{const x=out.textContent;if(!x||x===T('இன்னும் ஸ்கேன் செய்யவில்லை','Not scanned yet'))return;Pg.closeAll();send(T('இந்தத் திரையை விளக்கு:\n','Explain this screen:\n')+x.slice(0,3000))});
 p.appendChild(out)});

Pg.def('camera',()=>T('கேமரா','Camera'),p=>{
 UI.note(p,T('படம் எடுத்து YUNA-விடம் அனுப்பலாம் (அதிகபட்சம் 4 இணைப்புகள்).','Take a photo and send it to YUNA (max 4 attachments).'));
 UI.btn(p,T('கேமராவைத் திற','Open camera'),()=>{Pg.closeAll();YUNA.pick('cam')});
 UI.btn(p,T('படங்களிலிருந்து','From gallery'),()=>{Pg.closeAll();YUNA.pick('img')})});
