'use strict';
/* Reply outputs (spec sec.7 / B3): image, video, file(PDF/Word/Excel/PPT), html preview, code. Cloud sends j.outputs[].
   {type:'image|video|file|html|code', name, mime, url(https) | b64, poster, size, lang, text, status:'working|done|error', progress, message} */
const Out={
 src(o){if(o.url&&/^https:\/\//i.test(o.url))return o.url;return o.b64?'data:'+(o.mime||'application/octet-stream')+';base64,'+o.b64:''},
 render(box,list){(Array.isArray(list)?list:[]).slice(0,6).forEach(o=>{try{box.appendChild(Out.one(o||{}))}catch(e){console.error('Out',e)}});pin()},
 strip(list){return(Array.isArray(list)?list:[]).filter(o=>o&&o.status!=='working').map(o=>{const c=Object.assign({},o);delete c.b64;return c}).filter(o=>o.url||o.text)},
 fail(w,msg,o){w.className='out out-err';w.textContent='';w.appendChild(el('div','',msg||T('இதை ஏற்ற முடியவில்லை','Could not load this')));
  if(o&&o.retry)UI.btn(w,t('retry'),o.retry);return w},
 save(o){const s=Out.src(o);if(!s){toast(T('சேமிக்க முடியவில்லை','Cannot save'));return}const a=el('a');a.href=s;a.download=o.name||'yuna-file';a.rel='noopener';document.body.appendChild(a);a.click();a.remove()},
 one(o){const w=el('div','out out-'+(o.type||'file')),s=Out.src(o);
  if(o.status==='working'){w.appendChild(el('div','shim'));w.appendChild(el('small','',(o.progress!=null?Math.round(o.progress)+'% · ':'')+T('உருவாக்குகிறேன்…','Creating…')));return w}
  if(o.status==='error')return Out.fail(w,o.message||T('உருவாக்க முடியவில்லை','Generation failed'),o);
  if(o.type==='image'){if(!s)return Out.fail(w);const im=el('img');im.loading='lazy';im.alt=o.name||'';im.src=s;im.onerror=()=>Out.fail(w);im.onclick=()=>Out.box(Out.view(o,s),o);w.appendChild(im);return w}
  if(o.type==='video'){if(!s)return Out.fail(w);const v=el('video');v.controls=true;v.playsInline=true;v.preload='metadata';if(o.poster&&/^https:|^data:image/.test(o.poster))v.poster=o.poster;v.src=s;v.onerror=()=>Out.fail(w,T('வீடியோவை இயக்க முடியவில்லை','Cannot play video'));w.appendChild(v);return w}
  if(o.type==='code'){w.appendChild(md('```'+(o.lang||'')+'\n'+String(o.text||'')+'\n```'));return w}
  if(o.type==='html'){const html=String(o.text||'');const bar=el('div','ob'),fr=el('iframe');fr.setAttribute('sandbox','');fr.srcdoc=html;fr.title=o.name||'preview';
   const code=md('```html\n'+html+'\n```');code.firstChild&&(code.firstChild.style.display='none');const holder=el('div');holder.appendChild(code);
   const bp=el('button','',T('முன்னோட்டம்','Preview')),bc=el('button','',T('Code','Code')),br=el('button','',T('இயக்கு','Run')),bf=el('button','',T('முழுத்திரை','Full'));
   const show=p=>{fr.style.display=p?'block':'none';holder.firstChild.firstChild.style.display=p?'none':'block'};
   bp.onclick=()=>show(1);bc.onclick=()=>show(0);br.onclick=()=>{UI.ask(T('இந்தப் பக்கத்தில் script இயங்கும். தொடரவா?','Scripts on this page will run. Continue?'),()=>{fr.setAttribute('sandbox','allow-scripts');fr.srcdoc=html;show(1)},T('இயக்கு','Run'))};
   bf.onclick=()=>{const f2=el('iframe');f2.setAttribute('sandbox',fr.getAttribute('sandbox'));f2.srcdoc=html;Out.box(f2,o)};
   [bp,bc,br,bf].forEach(b=>bar.appendChild(b));w.appendChild(bar);w.appendChild(fr);w.appendChild(holder);return w}
  /* file: PDF / Word / Excel / PowerPoint / other */
  const ext=((o.name||'').split('.').pop()||'').toLowerCase(),ic={pdf:'▤',doc:'▤',docx:'▤',xls:'▦',xlsx:'▦',ppt:'▣',pptx:'▣'}[ext]||'▤';
  const c=el('div','fcard');c.appendChild(el('b','',ic));const m=el('div','fm');m.appendChild(el('span','',o.name||T('கோப்பு','File')));
  m.appendChild(el('small','',[ext.toUpperCase(),o.size?(o.size>1048576?(o.size/1048576).toFixed(1)+' MB':Math.max(1,Math.round(o.size/1024))+' KB'):''].filter(Boolean).join(' · ')));c.appendChild(m);
  const op=el('button','',T('திற','Open')),sv=el('button','',T('சேமி','Save'));op.onclick=()=>{if(/^https:/i.test(o.url||''))window.open(o.url,'_blank');else Out.save(o)};sv.onclick=()=>Out.save(o);
  if(!s)return Out.fail(w);c.appendChild(op);c.appendChild(sv);w.appendChild(c);return w},
 view(o,s){const i=el('img');i.alt=o.name||'';i.src=s;return i},
 /* full-screen viewer; Back closes it */
 box(node,o){Out.closeBox();const d=el('div');d.id='lightbox';const x=el('button','x','✕');x.setAttribute('aria-label','close');x.onclick=Out.closeBox;d.appendChild(x);d.appendChild(node);
  if(o&&node.tagName==='IMG'){const s=el('button','sv',T('சேமி','Save'));s.onclick=()=>Out.save(o);d.appendChild(s)}document.body.appendChild(d)},
 closeBox(){const d=$('#lightbox');if(d){d.remove();return true}return false}
};
