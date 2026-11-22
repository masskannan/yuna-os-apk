'use strict';
/* Page 03: attachments - chips, 1280px JPEG 0.85, PDF pages (<=8), payload */
YUNA.att=[];
YUNA.register('attach',()=>{
 const bar=document.createElement('div');bar.id='attBar';$('#dock').insertBefore(bar,$('#inp'));const fi=$('#fileIn');
 function render(){bar.innerHTML='';bar.style.display=YUNA.att.length?'flex':'none';
  YUNA.att.forEach((a,i)=>{const c=document.createElement('div');c.className='chip';
   if(a.kind==='image'){const im=document.createElement('img');im.alt='';im.src=a.thumb;c.appendChild(im)}else{const b=document.createElement('b');b.textContent='▤';c.appendChild(b)}
   const s=document.createElement('span');s.textContent=a.name;c.appendChild(s);
   const x=document.createElement('button');x.textContent='✕';x.setAttribute('aria-label','remove');x.onclick=()=>{YUNA.att.splice(i,1);render()};c.appendChild(x);bar.appendChild(c)});kbUpdate()}
 async function addImage(f){const bm=await createImageBitmap(f),sc=Math.min(1,1280/Math.max(bm.width,bm.height)),c=document.createElement('canvas');c.width=Math.round(bm.width*sc);c.height=Math.round(bm.height*sc);c.getContext('2d').drawImage(bm,0,0,c.width,c.height);const u=c.toDataURL('image/jpeg',.85);YUNA.att.push({kind:'image',mime:'image/jpeg',b64:u.split(',')[1],name:f.name||'image',thumb:u})}
 function addPdf(f){let p=[];try{p=JSON.parse(call('renderPdfPagesBase64',f.name)||'[]')}catch(_){}
  if(!Array.isArray(p)||!p.length){toast(t('pdfFail'));return}
  YUNA.att.push({kind:'pdf',name:f.name,pages:p.slice(0,8).map((x,i)=>typeof x==='string'?{n:i+1,b64:x}:{n:x.n||i+1,b64:x.b64||x.data||''})})}
 fi.onchange=async e=>{const f=e.target.files[0];e.target.value='';if(!f)return;if(YUNA.att.length>=4){toast(t('attMax'));return}
  try{if(f.type.startsWith('image/'))await addImage(f);else if(/pdf/i.test(f.type+f.name))addPdf(f);else toast(t('pdfFail'))}catch(_){toast(t('pdfFail'))}render()};
 YUNA.pick=k=>{fi.removeAttribute('capture');if(k==='voice'){startMic();return}fi.accept=k==='file'?'application/pdf,image/*':'image/*';if(k==='cam')fi.setAttribute('capture','environment');fi.click()};
 $$('[data-a]').forEach(b=>b.onclick=()=>{$('#attachSheet').classList.remove('on');scrim();YUNA.pick(b.dataset.a)});
 YUNA.takeAtt=()=>{const a=YUNA.att.splice(0);render();return a};
});
