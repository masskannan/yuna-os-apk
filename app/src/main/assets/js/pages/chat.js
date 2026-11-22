'use strict';
/* Page 01-02: chat send/stop, cloud protocol, OS actions, reply actions, TTS */
let abort=null,tick=0;
const Yuna={
 sid(){let s=ls.g('yuna_cloud_sid');if(!s){s='ui5-'+Date.now().toString(36)+Math.random().toString(36).slice(2,7);ls.s('yuna_cloud_sid',s)}return s},
 redact(x){try{if(call('checkSensitive',x)===true)return '[REDACTED_BY_RUST_SHIELD]'}catch(_){}return x.replace(/\b\d{4,8}\b/g,'[REDACTED_NUM]').replace(/\b(otp|cvv|pin|upi|password|mpin)\b/gi,'[REDACTED_KEY]')},
 contacts(){try{return JSON.parse(call('getSyncedContactsJson')||'[]')}catch(_){return[]}},
 /* one cloud round-trip. returns {text,actions}. throws Error('nourl') / AbortError / network */
 async ask(prompt,att,ctrl,sid,live){const url=ls.g('yuna_cloud_url');if(!url)throw new Error('nourl');
  const pl={prompt:Yuna.redact(prompt),sessionId:sid||Yuna.sid(),contacts:Yuna.contacts()};
  const tools=window.Tools?Tools.selected(live):[];if(tools.length)pl.tools=tools;
  if(att&&att.length)pl.attachments=att.map(a=>a.kind==='image'?{kind:'image',mime:a.mime,b64:a.b64}:{kind:'pdf',pages:a.pages});
  const tm=setTimeout(()=>ctrl.abort(),30000);
  try{const r=await fetch(url,{method:'POST',signal:ctrl.signal,headers:{'Content-Type':'application/json'},body:JSON.stringify(pl)});
   if(!r.ok)throw new Error('http'+r.status);const j=await r.json();
   const text=j.reply||j.text||j.answer||j.response||j.result||'';
   const actions=Array.isArray(j.actions)?j.actions:(j.action&&j.action!=='NONE'?[{action:j.action,target:j.target||''}]:[]);
   const outputs=Array.isArray(j.outputs)?j.outputs:[];
   if(!text&&!actions.length&&!outputs.length)throw new Error('empty');
   return{text:text||((actions.length||outputs.length)?T('சரி','Done'):''),actions,outputs}}
  finally{clearTimeout(tm)}},
 spk:null,
 say(x,b){if(Yuna.spk){const w=Yuna.spk===b;Yuna.sayDone();call('stopTts');if(w)return}Yuna.spk=b;b.classList.add('on');b.textContent='■';Yuna.tts(x,1);if(!B())setTimeout(()=>Yuna.spk===b&&Yuna.sayDone(),1500)},
 sayDone(){const b=Yuna.spk;Yuna.spk=null;if(b){b.classList.remove('on');b.textContent='🔊'}},
 tts(x,force){const p=ls.g('yuna_voice_persona')||'YUNA';if(p==='SILENT'&&!force)return;
  x=String(x||'').replace(/```[\s\S]*?```/g,' ').replace(/https?:\/\/\S+/g,' ').replace(/[*_#`>~|]/g,'').replace(/\s+/g,' ').trim().slice(0,900);if(!x)return;
  if(B()&&typeof B().speakSmartWithVoice==='function')call('speakSmartWithVoice',x,p,ls.g('yuna_cloud_url')||'',ls.g('yuna_ta_voice')||'ta-SG-VenbaNeural');else call('speakSmart',x)}
};
function copyText(x){const fb=()=>{const a=document.createElement('textarea');a.value=x;document.body.appendChild(a);a.select();try{document.execCommand('copy');toast(t('copied'))}catch(_){}a.remove()};try{navigator.clipboard.writeText(x).then(()=>toast(t('copied')),fb)}catch(_){fb()}}
/* reply action row: copy, speak one message, 👍/👎 (stored in the chat's own message, no new key), regenerate (last reply only), share */
function actions(box,a,txt,m,last){$$('.acts .rg').forEach(x=>x.remove());box.querySelectorAll('.acts').forEach(x=>x.remove());const r=document.createElement('div');r.className='acts';
 const mk=(i,l,f,c)=>{const b=document.createElement('button');b.textContent=i;b.setAttribute('aria-label',l);if(c)b.className=c;b.onclick=()=>f(b);r.appendChild(b);return b};
 mk('⧉','copy',()=>copyText(a));
 mk('🔊','speak',b=>Yuna.say(a,b),'spk');
 const up=mk('👍','like',()=>vote(1),'fb'),dn=mk('👎','dislike',()=>vote(-1),'fb');
 const paint=()=>{[[up,1],[dn,-1]].forEach(([b,v])=>{const on=m&&m.f===v;b.classList.toggle('on',on);b.setAttribute('aria-pressed',on)})};
 function vote(v){if(!m)return;if(m.f===v)delete m.f;else m.f=v;paint();save();if(m.f)toast(T('கருத்துக்கு நன்றி','Thanks for the feedback'))}
 paint();
 if(last!==false)mk('↻','retry',()=>regen(box,txt),'rg');
 mk('⤴','share',()=>navigator.share?navigator.share({text:a}).catch(()=>{}):copyText(a));box.appendChild(r)}
function stopSpeakUI(){if(Yuna.spk){Yuna.sayDone();call('stopTts')}}
function setBusy(v){sending=v;$('#send').textContent=v?'■':'↑'}
function osPill(box,x){if(!x)return;box.appendChild(el('div','os-pill',x));pin()}
/* one request/response cycle that fills `box` (used by send and regenerate) */
async function answer(txt,att,box,viaVoice){setBusy(true);
 const st=document.createElement('small'),body=document.createElement('div');st.textContent=t('THINK');box.appendChild(st);box.appendChild(body);
 const t0=Date.now();clearInterval(tick);tick=setInterval(()=>{const s=(Date.now()-t0)/1000;if(s>20)st.textContent=t('still')+' · '+Math.floor(s)+'s';else if(s>8)st.textContent=t('THINK')+' '+Math.floor(s)+'s'},1000);
 abort=new AbortController();const ch=cur;if(!ch.sid)ch.sid=Yuna.sid()+'-'+ch.id;     /* each chat has its own cloud session; `ch` pins the reply to THIS chat even if the user switches chats meanwhile */
 box._att=att;
 try{const d=await Yuna.ask(txt||t('describe'),att,abort,ch.sid);st.textContent=t('WRITE');
  body.appendChild(md(d.text));Out.render(box,d.outputs);const mm={r:'y',t:d.text,o:Out.strip(d.outputs)};ch.msgs.push(mm);st.remove();actions(box,d.text,txt,mm,true);
  Act.run(d.actions,o=>osPill(box,o)).forEach(o=>osPill(box,o));
  if(viaVoice||Live.on)Yuna.tts(d.text)
 }catch(e){st.remove();if(e.name==='AbortError'){body.textContent='■ '+t('stop')}else{body.className='err';body.textContent=(e.message==='nourl'?t('noUrl'):t('noNet'));toast(body.textContent,e.message==='nourl'?()=>Pg.open('models'):()=>regen(box,txt),e.message==='nourl'?T('அமை','Set'):0)}}
 finally{clearInterval(tick);Tools.once.clear();setBusy(false);abort=null;if(ch.msgs.length){const i=sessions.findIndex(x=>x.id===ch.id);i<0?sessions.unshift(ch):sessions[i]=ch;persistSessions();renderHist()}pin()}}
async function send(txt){const fromInput=txt===undefined;txt=(fromInput?inp.value:txt).trim();
 if((!txt&&!(fromInput&&YUNA.att.length))||sending)return;
 const att=fromInput?YUNA.takeAtt():[];if(recording)stopMic(true);
 if(fromInput){inp.value='';grow()}pinned=true;const viaVoice=YUNA.voiceTurn;YUNA.voiceTurn=false;
 const u=add('u',txt);att.forEach(a=>{if(a.thumb){const im=document.createElement('img');im.src=a.thumb;im.className='thumb';im.alt='';u.appendChild(im)}else{const d=document.createElement('div');d.textContent='▤ '+a.name;u.appendChild(d)}});
 cur.msgs.push({r:'u',t:txt||('▣ '+att.length),na:att.length||undefined});if(!cur.title)cur.title=(txt||(att[0]&&att[0].name)||'').slice(0,40);
 await answer(txt,att,add('y',''),viaVoice)}
/* regenerate: replaces the last reply in place (no duplicate question) */
function regen(box,txt){if(sending)return;const i=cur.msgs.length-1;if(i>=0&&cur.msgs[i].r==='y')cur.msgs.pop();const att=box._att||[];box.innerHTML='';pinned=true;answer(txt,att,box)}
/* home: greeting + starter prompts */
function greet(){const h=new Date().getHours();return h<5?t('hello'):h<12?T('காலை வணக்கம்','Good morning'):h<17?T('மதிய வணக்கம்','Good afternoon'):h<21?T('மாலை வணக்கம்','Good evening'):t('hello')}
const STARTERS=()=>[[T('இன்றைய நாளைத் திட்டமிடு','Plan my day'),T('இன்றைய நாளை எளிமையாகத் திட்டமிட உதவு','Help me plan my day simply')],[T('எளிமையாக விளக்கு','Explain simply'),T('ஒரு கடினமான தலைப்பை எளிமையாக விளக்கு','Explain a hard topic simply')],[T('Code எழுது','Write code'),T('ஒரு சிறிய Python நிரல் எழுதிக் காட்டு','Write a small Python program')],[T('மின்னஞ்சல் வரை','Draft an email'),T('ஒரு தொழில்முறை மின்னஞ்சல் வரைந்து தா','Draft a professional email')]];
function helloFill(){const h=$('#hello');if(!h)return;$('b',h).textContent=greet();const old=$('#chips');old&&old.remove();const c=el('div');c.id='chips';STARTERS().forEach(([l,q])=>{const b=el('button','',l);b.onclick=()=>send(q);c.appendChild(b)});h.appendChild(c)}
function showHello(){chat.innerHTML='<div id="hello"><b></b><span data-i="helloSub"></span></div>';helloFill();applyLang()}
YUNA.register('hello',showHello);
$('#send').onclick=()=>sending?(abort&&abort.abort()):send();
YUNA.register('toBottom',()=>{const b=document.createElement('button');b.id='toBottom';b.textContent='↓';b.setAttribute('aria-label','bottom');document.body.appendChild(b);
 chat.addEventListener('scroll',()=>{b.style.display=pinned?'none':'flex'},{passive:true});b.onclick=()=>{pinned=true;chat.scrollTop=chat.scrollHeight}});
