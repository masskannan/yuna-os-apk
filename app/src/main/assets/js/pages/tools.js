'use strict';
/* Batch 6: 21 tools hub, 22 MCP, 23 API plugins, 24 web research */
const V4_TOOLS=[
 {id:'web.search',k:'toggle',g:'research',ta:'Web தேடல்',en:'Web search'},{id:'phone.actions',k:'toggle',g:'work',ta:'போன் செயல்கள்',en:'Phone actions'},
 {id:'wa.intel',k:'toggle',g:'work',ta:'WhatsApp Intel',en:'WhatsApp Intel'},{id:'notes.export',k:'toggle',g:'work',ta:'குறிப்பாக ஏற்று',en:'Export as note'},
 {id:'files.pdf',k:'launch',g:'work',ta:'PDF / கோப்பு',en:'PDF / file'},{id:'camera',k:'launch',g:'see',ta:'கேமரா',en:'Camera'},{id:'screen.vision',k:'launch',g:'see',ta:'திரைப் பார்வை',en:'Screen vision'},
 {id:'img',k:'soon',g:'make',ta:'படம் உருவாக்கு',en:'Create image'},{id:'video',k:'soon',g:'make',ta:'வீடியோ',en:'Video'},{id:'canvas',k:'soon',g:'make',ta:'Canvas & Code',en:'Canvas & Code'},
 {id:'deep',k:'soon',g:'research',ta:'Deep research',en:'Deep research'},{id:'gmail',k:'soon',g:'work',ta:'Gmail / Calendar',en:'Gmail / Calendar'},{id:'agent',k:'soon',g:'see',ta:'Browser agent',en:'Browser agent'}];
const GROUPS={make:['உருவாக்கு','Create'],research:['ஆராய்ச்சி','Research'],work:['வேலை','Work'],see:['பார்வை & Agent','Vision & Agent']};
/* V4_STATE: only toggle tools persist (new key yuna_v4_state). launch tools are ON for ONE send only and never persisted. */
const Tools={once:new Set(),state(){const s=UI.json('yuna_v4_state',{});return s&&typeof s==='object'?s:{}},
 set(id,v){const s=Tools.state();s[id]=!!v;ls.s('yuna_v4_state',JSON.stringify(s))},
 selected(live){const s=Tools.state();return V4_TOOLS.filter(x=>(x.k==='toggle'&&s[x.id])||(!live&&Tools.once.has(x.id))).map(x=>x.id)}};
function launchTool(id){
 if(id==='files.pdf'){Tools.once.add(id);Pg.closeAll();YUNA.pick('file')}
 else if(id==='camera'){Tools.once.add(id);Pg.closeAll();YUNA.pick('cam')}
 else if(id==='screen.vision'){Tools.once.add(id);Pg.open('vision')}}
Pg.def('tools',()=>T('கருவிகள் மையம்','Tools hub'),p=>{
 const s=Tools.state();
 Object.keys(GROUPS).forEach(g=>{UI.sec(p,L==='ta'?GROUPS[g][0]:GROUPS[g][1]);
  V4_TOOLS.filter(x=>x.g===g).forEach(x=>{const n=L==='ta'?x.ta:x.en;
   if(x.k==='toggle')UI.row(p,n,UI.sw(!!s[x.id],v=>Tools.set(x.id,v)));
   else if(x.k==='launch')UI.row(p,n,UI.pill(T('திற','Open'),()=>launchTool(x.id)),T('அடுத்த ஒரு அனுப்புதலுக்கு மட்டும்','For the next send only'));
   else UI.row(p,n,UI.pill(t('soon'),()=>toast(T('இது விரைவில் வரும்','Coming soon'))))})});
UI.link(p,'MCP / Plugins','mcp');UI.link(p,'API','api');UI.link(p,T('Web ஆராய்ச்சி','Web research'),'research')});

function connector(kind){return(p)=>{
 UI.note(p,T('வெளி சேவைகளைப் பாதுகாப்பாக இணைக்கும் வசதி விரைவில் வரும். இப்போது எதுவும் சேமிக்கப்படாது.','Connecting outside services safely is coming soon. Nothing is stored for now.'));
 const f=el('input','fld');f.type='url';f.placeholder=kind==='mcp'?'Server URL (https://…)':'API base URL (https://…)';p.appendChild(f);
 const k=el('input','fld');k.type='password';k.placeholder=T('API விசை (திரையில் காட்டப்படாது)','API key (never shown)');k.autocomplete='off';p.appendChild(k);
 UI.sec(p,T('அனுமதிகள்','Permissions'));[[T('படிக்க','Read'),1],[T('எழுத','Write'),0],[T('அழிக்க','Delete'),0]].forEach(([n,on])=>UI.row(p,n,UI.sw(!!on,()=>{}),on?'':T('ஒவ்வொரு முறையும் உறுதி கேட்கும்','Asks every time')));
 UI.sec(p,T('4 படிச் சோதனை','4-step check'));const ol=el('ol','steps');['இணை','கருவிப் பட்டியல்','அனுமதி','தயார்'].forEach((a,i)=>ol.appendChild(el('li','',L==='ta'?a:['Connect','Tool list','Permission','Ready'][i])));p.appendChild(ol);
 UI.btn(p,T('இணை','Connect'),()=>{k.value='';toast(T('இந்த வசதி விரைவில் வரும்','This feature is coming soon'))},true)}}
Pg.def('mcp','MCP / Plugins',connector('mcp'));Pg.def('api','API',connector('api'));

Pg.def('research',()=>T('Web ஆராய்ச்சி','Web research'),p=>{
 UI.row(p,T('Web தேடல்','Web search'),UI.sw(!!Tools.state()['web.search'],v=>Tools.set('web.search',v)),T('இயக்கினால் கோரிக்கையுடன் "web.search" அனுப்பப்படும்','When on, "web.search" is sent with your message'));
 UI.note(p,T('"தேடுகிறேன்…" நிலை, தேடல் உண்மையில் நடந்தால் மட்டுமே காட்டப்படும்; "இன்று", "என்ன" போன்ற சொற்கள் அதைத் தூண்டாது.','"Searching…" shows only when a search really runs; words like "today" or "what is" never trigger it.'))});
