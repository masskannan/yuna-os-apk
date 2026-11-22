'use strict';
/* Batch 5: 17 permissions, 18 security, 19 memory, 20 RAG knowledge */
const T0=(a,e)=>({ta:a,en:e}),LB=o=>o&&o.ta!==undefined?(L==='ta'?o.ta:o.en):o;
/* Permission hub: keys are the ones the APK reports (getDetailedPermissions) + writeSettings from getPermissionStatus. Unknown extra keys are still listed. */
const PERMS=[
 ['core',['mic',T0('மைக்','Microphone'),T0('குரல் உள்ளீடு, நேரலை','Voice input and Live')],['accessibility',T0('அணுகல்தன்மை (Accessibility)','Accessibility'),T0('Back/Home, திரை வாசிப்பு, தட்டுதல்','Back/Home, screen reading, taps')],['overlay',T0('பிற app மேல் காட்டு','Display over other apps'),T0('மிதக்கும் பந்து','Floating ball')]],
 ['calls',['contacts',T0('தொடர்புகள்','Contacts'),T0('பெயர் சொல்லி அழைக்க','Call by name')],['call',T0('தொலைபேசி அழைப்பு','Phone calls'),T0('நேரடியாக அழைக்க','Dial directly')],['phoneState',T0('தொலைபேசி நிலை','Phone state'),T0('அழைப்பவர் பெயரை அறிய','Know who is calling')]],
 ['extra',['notifications',T0('அறிவிப்பு அணுகல்','Notification access'),T0('WhatsApp செய்திகளைப் படிக்க','Read WhatsApp messages')],['battery',T0('பேட்டரி கட்டுப்பாடு விலக்கு','Battery optimisation exempt'),T0('பின்னணியில் தொடர்ந்து இயங்க','Keep running in background')],['assistant',T0('இயல்பு உதவியாளர்','Default assistant'),T0('Home பொத்தானால் YUNA','YUNA on Home button')],['writeSettings',T0('அமைப்புகளை மாற்று','Modify settings'),T0('பிரகாசம், ஒலி','Brightness, volume')]]
];
const PGRP={core:['அடிப்படை (தேவை)','Essential'],calls:['அழைப்பு & தொடர்பு','Calls & contacts'],extra:['கூடுதல்','Optional']};
function permAll(){const j=permJson();let s={};try{s=JSON.parse(call('getPermissionStatus')||'{}')||{}}catch(_){}return Object.assign({},s,j)}
function permMissing(){if(!B())return[];const j=permAll();return['mic','accessibility'].filter(k=>j[k]===false)}
Pg.def('permissions',()=>t('perms'),p=>{
 const draw=()=>{p.innerHTML='';const j=permAll(),known=new Set();banner();
  if(!B()||!Object.keys(j).length){UI.empty(p,'🔒',T('அனுமதி விவரம் கிடைக்கவில்லை','Permission details unavailable'),T('இந்தத் திரை YUNA மொபைல் app-ல் மட்டும் வேலை செய்யும்','This screen works inside the YUNA mobile app only'));return}
  const all=PERMS.flatMap(g=>g.slice(1)).map(x=>x[0]),on=all.filter(k=>j[k]===true).length;
  UI.note(p,T('தேவையான அனுமதிகள்: ','Permissions on: ')+on+' / '+all.length+' · '+T('"இயக்கு" தட்டினால் Android அமைப்புகள் திறக்கும்; திரும்பி வந்ததும் நிலை தானாகப் புதுப்பிக்கப்படும்.','Tap "Grant" to open Android Settings; the status refreshes when you come back.'));
  const row=(k,n,d)=>{n=LB(n);d=LB(d);known.add(k);const v=j[k];const b=UI.pill(v===true?(T('இயக்கத்தில் ✓','ON ✓')):(v===false?T('இயக்கு','Grant'):T('அமை','Open')),()=>{call('openPermissionSettings',k)});b.classList.add(v===true?'on':'warn');b.setAttribute('aria-label',n);UI.row(p,n,b,d)};
  PERMS.forEach(g=>{UI.sec(p,L==='ta'?PGRP[g[0]][0]:PGRP[g[0]][1]);g.slice(1).forEach(x=>row(x[0],x[1],x[2]))});
  const more=Object.keys(j).filter(k=>!known.has(k));if(more.length){UI.sec(p,T('மற்றவை','Other'));more.forEach(k=>row(k,k,''))}};
 draw();clearInterval(p._perm);p._perm=setInterval(()=>{const e=$('#pg-permissions');if(!e||!e.classList.contains('on')){clearInterval(p._perm);return}draw()},2000)});
YUNA.permRefresh=()=>{if(Pg.stack[Pg.stack.length-1]==='permissions')Pg.refresh();banner()};
/* entry banner: no stored state. Shows when mic or accessibility is OFF; hidden for this session once dismissed. */
let bannerOff=false;
function banner(){const old=$('#permBanner');const m=bannerOff?[]:permMissing();if(!m.length){old&&old.remove();return}
 const b=old||el('div');b.id='permBanner';b.textContent='';b.appendChild(el('span','',T(m.length+' அனுமதி தேவை — YUNA முழுமையாக வேலை செய்ய','Needs '+m.length+' permission(s) for YUNA to work fully')));
 const g=el('button','',T('அமை','Set up'));g.onclick=()=>Pg.open('permissions');const x=el('button','',T('✕','✕'));x.setAttribute('aria-label','close');x.onclick=()=>{bannerOff=true;banner()};b.appendChild(g);b.appendChild(x);if(!old)$('#app').insertBefore(b,$('#chat'))}
YUNA.register('permBanner',()=>setTimeout(banner,400));

Pg.def('security',()=>T('பாதுகாப்பு & உரிமையாளர்','Security & owner'),p=>{
 UI.sec(p,T('OS செயல்கள்','OS actions'));const auto=Act.mode()==='AUTO';
 UI.row(p,T('உறுதிப்படுத்தாமல் செய்','Run without asking'),UI.sw(auto,(v,b)=>{
  if(v){b.classList.remove('on');UI.ask(T('அழைப்பு, பூட்டு போன்ற செயல்கள் கேட்காமலேயே நடக்கும். இயக்கவா?','Calls, lock-screen and similar actions will run without asking. Enable?'),()=>{ls.s('yuna_safety_mode','AUTO');b.classList.add('on')},T('இயக்கு','Enable'))}
  else ls.s('yuna_safety_mode','CONFIRM')}),T('அணைந்திருந்தால்: DIAL, LOCK_SCREEN, CLEAR_RECENTS உறுதி கேட்கும்','Off: DIAL, LOCK_SCREEN, CLEAR_RECENTS ask first'));
 UI.sec(p,T('உரிமையாளர் சரிபார்ப்பு','Owner check'));
 const st=el('p','note','');UI.btn(p,T('கைரேகை/முகம் சரிபார்','Verify with biometric'),()=>{const r=call('verifyOwnerBiometric');st.className='note '+(r===false||r==='false'?'bad':'ok');st.textContent=r===undefined?T('YUNA மொபைல் app-ல் மட்டும் கிடைக்கும்','Available in the YUNA mobile app only'):(r===false||r==='false'?T('சரிபார்ப்பு தோல்வி','Verification failed'):T('சரிபார்க்கப்பட்டது','Verified'))});p.appendChild(st);
 UI.sec(p,T('நம்பகமான அழைப்புகள்','Trusted contacts'));const m=Act.trusted(),ks=Object.keys(m);
 if(!ks.length)UI.note(p,T('எதுவும் இல்லை','None yet'));
 ks.forEach(n=>UI.row(p,n,UI.pill(T('நீக்கு','Remove'),()=>UI.ask(T('நீக்கவா? ','Remove? ')+n,()=>{const x=Act.trusted();delete x[n];ls.s('yuna_trusted_calls',JSON.stringify(x));Pg.refresh()})),m[n]));
 UI.sec(p,T('உணர்திறன் தகவல்','Sensitive data'));UI.note(p,T('OTP, PIN, கடவுச்சொல், 4–8 இலக்க எண்கள் Cloud-க்கு அனுப்பும் முன் மறைக்கப்படும்.','OTPs, PINs, passwords and 4–8 digit numbers are masked before reaching Cloud.'))});

const notes=()=>{const n=UI.json('yuna_notes_v1',[]);return Array.isArray(n)?n:[]};
Pg.def('memory',()=>t('memory'),p=>{
 const n=notes();if(!n.length){UI.empty(p,'◈',T('நினைவில் எதுவும் இல்லை','Nothing remembered yet'));return}
 n.forEach((x,i)=>{const b=UI.pill('🗑',()=>{const all=notes(),g=all.splice(i,1)[0];ls.s('yuna_notes_v1',JSON.stringify(all));Pg.refresh();toast(t('deleted'),()=>{const a=notes();a.splice(Math.min(i,a.length),0,g);ls.s('yuna_notes_v1',JSON.stringify(a))},t('undo'))});b.setAttribute('aria-label','delete');b.style.minWidth='48px';UI.row(p,x.text,b,x.at)});
 UI.btn(p,T('எல்லாவற்றையும் அழி','Clear all'),()=>UI.ask(T('எல்லா நினைவுகளும் அழியும். தொடரவா?','All memories will be erased. Continue?'),()=>{ls.s('yuna_notes_v1','[]');Pg.refresh()},T('அழி','Erase')))});

Pg.def('rag',()=>T('அறிவுத் தளம் (RAG)','Knowledge (RAG)'),p=>{
 const has=typeof window.queryYunaRag==='function';
 UI.row(p,T('நிலை','Status'),UI.pill(has?T('தயார்','Ready'):t('soon')),has?'':T('சேவை இன்னும் இணைக்கப்படவில்லை','Service not connected yet'));
 UI.empty(p,'📚',T('ஆவணங்கள் இல்லை','No documents'),T('PDF/குறிப்புகளைச் சேர்த்து YUNA பதிலில் பயன்படுத்தும் வசதி விரைவில் வரும்.','Adding PDFs/notes for YUNA to use in answers is coming soon.'))});
