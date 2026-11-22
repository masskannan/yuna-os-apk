'use strict';
/* In-chat search: highlights matching messages, prev/next, Back closes it. No new storage. */
let findHits=[],findI=-1;
function closeFind(){const b=$('#findBar');if(!b||!b.classList.contains('on'))return false;b.classList.remove('on');$$('#chat .hit,#chat .cur').forEach(e=>e.classList.remove('hit','cur'));findHits=[];findI=-1;return true}
YUNA.register('find',()=>{
 const bar=$('#findBar'),q=$('#findQ'),n=$('#findN');
 function go(d){if(!findHits.length)return;findI=(findI+d+findHits.length)%findHits.length;$$('#chat .cur').forEach(e=>e.classList.remove('cur'));const e=findHits[findI];e.classList.add('cur');pinned=false;e.scrollIntoView({block:'center'});n.textContent=(findI+1)+'/'+findHits.length}
 function run(){$$('#chat .hit,#chat .cur').forEach(e=>e.classList.remove('hit','cur'));const f=q.value.trim().toLowerCase();findHits=[];findI=-1;
  if(f)$$('#chat .m').forEach(m=>{if(m.textContent.toLowerCase().includes(f)){m.classList.add('hit');findHits.push(m)}});
  n.textContent=f?(findHits.length?'':'0'):'';if(findHits.length)go(1)}
 q.oninput=run;q.onkeydown=e=>{if(e.key==='Enter')go(e.shiftKey?-1:1)};
 $('#findUp').onclick=()=>go(-1);$('#findDn').onclick=()=>go(1);$('#findX').onclick=closeFind;
 $('#mFind').textContent=T('உரையாடலில் தேடு','Search in chat');
 $('#mFind').onclick=()=>{closeMore();if(!$$('#chat .m').length){toast(t('histEmpty'));return}bar.classList.add('on');q.value='';n.textContent='';setTimeout(()=>q.focus(),50)}
});
/* re-apply language to labels that are set from JS (called by applyLang) */
function relabel(){const s=$('#mShare'),f=$('#mFind');s&&(s.textContent=T('உரையாடலைப் பகிர்','Share chat'));f&&(f.textContent=T('உரையாடலில் தேடு','Search in chat'));if($('#hello')&&typeof helloFill==='function')helloFill();typeof banner==='function'&&banner()}
