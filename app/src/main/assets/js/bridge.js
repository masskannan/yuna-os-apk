'use strict';
/* YunaBridge safe wrappers + localStorage + DOM helpers */
const $=s=>document.querySelector(s),$$=(s,r)=>[...(r||document).querySelectorAll(s)];
const B=()=>window.YunaBridge||null;
const call=(n,...a)=>{try{const b=B();return b&&typeof b[n]==='function'?b[n](...a):undefined}catch(e){return undefined}};
const ls={g:k=>{try{return localStorage.getItem(k)}catch(_){return null}},s:(k,v)=>{try{localStorage.setItem(k,v)}catch(_){}}};
window.YUNA=window.YUNA||{_r:[],register(n,f){this._r.push([n,f])},boot(){this._r.forEach(([n,f])=>{try{f()}catch(e){console.error('YUNA.'+n,e)}})}};
