'use strict';
/* Page 04: full chat history - search, date groups, delete + undo */
YUNA.register('history',()=>{
 const pg=$('#historyPage'),list=$('#histList'),q=$('#histQ');
 const persist=persistSessions;
 const day=ts=>{const d=new Date(ts),n=new Date();d.setHours(0,0,0,0);n.setHours(0,0,0,0);const k=Math.round((n-d)/864e5);return k<=0?'today':k===1?'yesterday':'earlier'};
 function render(){const f=q.value.trim().toLowerCase(),g={pinned:[],today:[],yesterday:[],earlier:[]};list.innerHTML='';let n=0;
  byPin().filter(s=>!f||(s.title||'').toLowerCase().includes(f)||s.msgs.some(m=>String(m.t).toLowerCase().includes(f))).forEach(s=>g[s.pin?'pinned':day(s.id)].push(s));
  ['pinned','today','yesterday','earlier'].forEach(k=>{if(!g[k].length)return;n++;const h=document.createElement('h4');h.textContent=k==='pinned'?T('📌 பின் செய்தவை','📌 Pinned'):t(k);list.appendChild(h);
   g[k].forEach(s=>{const r=document.createElement('div');r.className='hrow';
    const o=document.createElement('button');o.className='open';o.textContent=s.title||'…';o.onclick=()=>open(s);r.appendChild(o);
    [[s.pin?'📍':'📌','pin',()=>pinIt(s)],['✎','rename',()=>ren(s)],['🗑','delete',()=>del(s)]].forEach(([i,l,f])=>{const b=document.createElement('button');b.textContent=i;b.setAttribute('aria-label',l);b.onclick=f;r.appendChild(b)});list.appendChild(r)})});
  if(!n){const e=document.createElement('div');e.className='hempty';e.textContent=t('histEmpty');list.appendChild(e)}}
 function open(s){openSession(s);pg.classList.remove('on');closeDrawer()}
 function pinIt(s){if(s.pin)delete s.pin;else s.pin=1;persist();render();renderHist();toast(s.pin?T('பின் செய்யப்பட்டது','Pinned'):T('பின் நீக்கப்பட்டது','Unpinned'))}
 function ren(s){UI.prompt(T('உரையாடலுக்குப் புதிய பெயர்','New chat name'),s.title,v=>{s.title=v.slice(0,80);persist();render();renderHist()},T('சேமி','Save'))}
 function del(s){const i=sessions.indexOf(s);if(i<0)return;sessions.splice(i,1);persist();
  if(cur===s||cur.id===s.id){cur={id:Date.now(),title:'',msgs:[]};showHello()}
  render();renderHist();toast(t('deleted'),()=>{sessions.splice(Math.min(i,sessions.length),0,s);persist();render();renderHist()},t('undo'))}
 $('#histAll').onclick=()=>{closeDrawer();q.value='';render();pg.classList.add('on')};
 q.oninput=render;
});
