'use strict';
/* toast */
/* ---- toast ---- */
let tt;function toast(m,retry,label){const e=$('#toast');e.textContent=m;if(retry){const b=document.createElement('button');b.textContent=label||t('retry');b.onclick=()=>{e.style.display='none';retry()};e.appendChild(b)}e.style.display='block';clearTimeout(tt);tt=setTimeout(()=>e.style.display='none',retry?6000:4000)}
