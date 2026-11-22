'use strict';
/* keyboard (--kb) + composer dock */
/* ---- viewport / keyboard: JS writes only --kb ---- */
const vv=window.visualViewport;let raf=0;
function kbUpdate(){cancelAnimationFrame(raf);raf=requestAnimationFrame(()=>{const h=vv?vv.height:innerHeight;let kb=vv?Math.max(0,innerHeight-vv.height-vv.offsetTop):0;if(kb<80)kb=0;document.documentElement.style.setProperty('--kb',kb+'px');document.documentElement.style.setProperty('--dock',($('#dock').offsetHeight)+'px');grow()})}
if(vv){vv.addEventListener('resize',kbUpdate);vv.addEventListener('scroll',kbUpdate)}addEventListener('resize',kbUpdate);

/* ---- composer ---- */
const inp=$('#inp');let composing=false,pinned=true,sending=false,partialBase='',recording=false;
const MAXL=8;
function grow(){const h=vv?vv.height:innerHeight;const lines=h<420?3:MAXL;const max=Math.min(lines*28,h*.4);inp.style.height='auto';inp.style.height=Math.min(Math.max(inp.scrollHeight,42),max)+'px';fade()}
function fade(){inp.classList.toggle('of',inp.scrollHeight>inp.clientHeight+2&&inp.scrollTop>2)}
inp.addEventListener('scroll',fade,{passive:true});
inp.addEventListener('input',()=>{if(!composing)grow()});
inp.addEventListener('compositionstart',()=>composing=true);
inp.addEventListener('compositionend',()=>{composing=false;grow()});
inp.addEventListener('focus',kbUpdate);

const chat=$('#chat');chat.addEventListener('scroll',()=>{pinned=chat.scrollHeight-chat.scrollTop-chat.clientHeight<60},{passive:true});
function pin(){if(pinned)chat.scrollTop=chat.scrollHeight}
let sessions=[];try{sessions=JSON.parse(ls.g('yuna_sessions')||'[]')}catch(_){}
let cur={id:Date.now(),title:'',msgs:[]};
/* pinned chats are never dropped by the 60-chat cap */
function persistSessions(){const p=sessions.filter(s=>s.pin),o=sessions.filter(s=>!s.pin).slice(0,Math.max(0,60-p.length));ls.s('yuna_sessions',JSON.stringify(sessions.filter(s=>s.pin||o.includes(s))))}
const byPin=()=>sessions.map((s,i)=>[s,i]).sort((a,b)=>(b[0].pin?1:0)-(a[0].pin?1:0)||a[1]-b[1]).map(x=>x[0]);
function save(){if(!cur.msgs.length)return;const i=sessions.findIndex(s=>s.id===cur.id);i<0?sessions.unshift(cur):sessions[i]=cur;persistSessions();renderHist()}
function openSession(s){if(sending&&abort)abort.abort();stopSpeakUI();cur=s;chat.innerHTML='';closeFind();s.msgs.forEach((m,i)=>add(m.r,m.t,1,m.o,m,{q:(s.msgs[i-1]||{}).t,last:i===s.msgs.length-1}));pinned=true;chat.scrollTop=chat.scrollHeight}
function renderHist(){$('#hist').innerHTML='';byPin().slice(0,30).forEach(s=>{const d=document.createElement('div');d.className='hist';d.textContent=(s.pin?'📌 ':'')+(s.title||'…');d.onclick=()=>{openSession(s);closeDrawer()};$('#hist').appendChild(d)})}
function add(role,text,quiet,outs,m,x){$('#hello')&&$('#hello').remove();const d=document.createElement('div');d.className='m '+(role==='u'?'u':'y');if(role==='u')d.textContent=text;else d.appendChild(md(text));chat.appendChild(d);if(outs&&outs.length)Out.render(d,outs);if(role!=='u'&&m&&text)actions(d,text,x&&x.q,m,x&&x.last);if(!quiet)pin();return d}
