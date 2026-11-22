'use strict';
/* safe markdown -> DOM (no innerHTML): paragraphs, headings, lists, code blocks with Copy, tables, **bold**, `code`, https links */
function md(src){const f=document.createDocumentFragment(),ln=String(src||'').replace(/\r/g,'').split('\n');let i=0;
 const inl=(p,s)=>{const re=/(`[^`]+`)|(\*\*[^*]+\*\*)|(\[[^\]]+\]\(https?:\/\/[^\s)]+\))/g;let last=0,m;
  while((m=re.exec(s))){if(m.index>last)p.appendChild(document.createTextNode(s.slice(last,m.index)));const x=m[0];
   if(x[0]==='`')p.appendChild(el('code','',x.slice(1,-1)));else if(x[0]==='*')p.appendChild(el('b','',x.slice(2,-2)));
   else{const q=/\[([^\]]+)\]\((https?:[^)]+)\)/.exec(x),a=el('a','',q[1]);a.href=q[2];a.target='_blank';a.rel='noopener';p.appendChild(a)}last=m.index+x.length}
  if(last<s.length)p.appendChild(document.createTextNode(s.slice(last)))};
 const isL=l=>/^\s*([-*•]|\d+[.)])\s+/.test(l),isT=(l,n)=>/^\|.*\|\s*$/.test(l)&&n&&/^\|[\s:|-]+\|\s*$/.test(n);
 while(i<ln.length){const l=ln[i];
  if(!l.trim()){i++;continue}
  if(/^```/.test(l)){const lang=l.slice(3).trim(),b=[];i++;while(i<ln.length&&!/^```/.test(ln[i]))b.push(ln[i++]);i++;const code=b.join('\n'),w=el('div','code'),h=el('div','ch');
   h.appendChild(el('span','',lang||'code'));const cb=el('button','',T('நகல்','Copy'));cb.onclick=()=>copyText(code);h.appendChild(cb);w.appendChild(h);w.appendChild(el('pre','',code));f.appendChild(w);continue}
  if(/^#{1,4}\s/.test(l)){const h=el('p','hd');inl(h,l.replace(/^#+\s*/,''));f.appendChild(h);i++;continue}
  if(isT(l,ln[i+1])){const cells=r=>r.trim().replace(/^\||\|$/g,'').split('|').map(c=>c.trim()),w=el('div','tbl'),tb=el('table');
   const th=el('tr');cells(l).forEach(c=>{const x=el('th');inl(x,c);th.appendChild(x)});tb.appendChild(th);i+=2;
   while(i<ln.length&&/^\|/.test(ln[i])){const tr=el('tr');cells(ln[i]).forEach(c=>{const x=el('td');inl(x,c);tr.appendChild(x)});tb.appendChild(tr);i++}w.appendChild(tb);f.appendChild(w);continue}
  if(isL(l)){const ord=/^\s*\d/.test(l),ul=el(ord?'ol':'ul');while(i<ln.length&&isL(ln[i])&&/^\s*\d/.test(ln[i])===ord){const li=el('li');inl(li,ln[i].replace(/^\s*(\d+[.)]|[-*•])\s+/,''));ul.appendChild(li);i++}f.appendChild(ul);continue}
  const b=[];while(i<ln.length&&ln[i].trim()&&!/^```/.test(ln[i])&&!isL(ln[i])&&!/^#{1,4}\s/.test(ln[i])&&!isT(ln[i],ln[i+1]))b.push(ln[i++]);const p=el('p');inl(p,b.join('\n'));f.appendChild(p)}
 return f}
