'use strict';
/* shared UI helpers + page stack (batches 2-7). T(ta,en) follows the UI language at render time. */
const T=(a,e)=>L==='ta'?a:e;
const el=(tag,cls,txt)=>{const e=document.createElement(tag);if(cls)e.className=cls;if(txt!=null)e.textContent=txt;return e};
const UI={
 sec(p,x){p.appendChild(el('h4','sec',x))},
 note(p,x){p.appendChild(el('p','note',x))},
 row(p,label,ctl,sub){const r=el('div','row'),l=el('div','lbl');l.appendChild(el('span','',label));if(sub)l.appendChild(el('small','',sub));r.appendChild(l);if(ctl)r.appendChild(ctl);p.appendChild(r);return r},
 pill(x,fn){const b=el('button','pill',x);if(fn)b.onclick=()=>fn(b);return b},
 sw(on,fn){const b=el('button','sw'+(on?' on':''));b.setAttribute('role','switch');b.setAttribute('aria-checked',!!on);b.onclick=()=>{const v=!b.classList.contains('on');b.classList.toggle('on',v);b.setAttribute('aria-checked',v);fn(v,b)};return b},
 link(p,label,id,sub){const r=UI.row(p,label,el('span','chev','›'),sub);r.classList.add('go');r.onclick=()=>Pg.open(id);return r},
 btn(p,x,fn,dis){const b=el('button','btn'+(dis?' dis':''),x);b.onclick=fn;p.appendChild(b);return b},
 empty(p,ic,a,b){const d=el('div','empty');d.appendChild(el('b','',ic));d.appendChild(el('div','',a));if(b)d.appendChild(el('small','',b));p.appendChild(d)},
 ask(msg,fn,yes){UI.closeDlg();const d=el('div');d.id='dlg';const c=el('div');c.appendChild(el('p','',msg));const b=el('div','bt'),n=el('button','',T('வேண்டாம்','Cancel')),y=el('button','y',yes||T('சரி','OK'));n.onclick=UI.closeDlg;y.onclick=()=>{UI.closeDlg();fn()};b.appendChild(n);b.appendChild(y);c.appendChild(b);d.appendChild(c);document.body.appendChild(d)},
 prompt(msg,val,fn,yes){UI.closeDlg();const d=el('div');d.id='dlg';const c=el('div');c.appendChild(el('p','',msg));const i=el('input');i.type='text';i.value=val||'';i.maxLength=80;i.className='dlgIn';c.appendChild(i);
  const b=el('div','bt'),n=el('button','',T('வேண்டாம்','Cancel')),y=el('button','y',yes||T('சரி','OK'));const ok=()=>{const v=i.value.trim();UI.closeDlg();if(v)fn(v)};n.onclick=UI.closeDlg;y.onclick=ok;i.onkeydown=e=>{if(e.key==='Enter')ok()};
  b.appendChild(n);b.appendChild(y);c.appendChild(b);d.appendChild(c);document.body.appendChild(d);setTimeout(()=>{i.focus();i.select()},50)},
 closeDlg(){const d=$('#dlg');if(d){d.remove();return true}return false},
 json(k,def){try{const v=JSON.parse(ls.g(k));return v==null?def:v}catch(_){return def}}
};
const Pg={reg:{},stack:[],
 def(id,title,render){this.reg[id]={title,render}},
 open(id,arg){const d=this.reg[id];if(!d)return;if(this.stack[this.stack.length-1]===id)return;
  let e=$('#pg-'+id);
  if(!e){e=el('section','page');e.id='pg-'+id;e.innerHTML='<div class="pg-inner"><div class="row"><button data-pgback aria-label="back">←</button><b></b><span></span></div><div class="pg-body"></div></div>';document.body.appendChild(e);e.querySelector('[data-pgback]').onclick=()=>window.handleYunaBackPress()}
  e.querySelector('b').textContent=typeof d.title==='function'?d.title():d.title;const b=e.querySelector('.pg-body');b.innerHTML='';
  try{d.render(b,arg)}catch(x){console.error('Pg.'+id,x);UI.empty(b,'⚠',T('இந்தத் திரையை ஏற்ற முடியவில்லை','Could not load this screen'))}
  e.style.zIndex=40+this.stack.length;this.stack.push(id);requestAnimationFrame(()=>e.classList.add('on'))},
 refresh(){const id=this.stack[this.stack.length-1];if(!id)return;this.stack.pop();const e=$('#pg-'+id);if(e){e.style.transition='none';e.classList.remove('on')}this.open(id);setTimeout(()=>{const x=$('#pg-'+id);x&&(x.style.transition='')},50)},
 back(){const id=this.stack.pop();if(!id)return false;const e=$('#pg-'+id);e&&e.classList.remove('on');return true},
 closeAll(){while(this.back());}};
