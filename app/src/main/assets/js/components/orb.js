'use strict';
/* YUNA orb: image, Tamil rings, kolam dots, state modes */
const NS='http://www.w3.org/2000/svg',osvg=$('#yunaTamilOrbSvg'),stage=$('#orbStage'),uyEl=$('#yunaUyirMei');
function mk(t,a,p){const e=document.createElementNS(NS,t);for(const k in a)e.setAttribute(k,a[k]);p.appendChild(e);return e}
function oring(r,fs,set,col,op,cls,dur){const g=mk('g',{class:'sp '+cls,style:'--t:'+dur+'s'},osvg),els=[];set.forEach((ch,i)=>{const e=mk('text',{x:160,y:160-r,'text-anchor':'middle','dominant-baseline':'central',fill:col,opacity:op,'font-size':fs,transform:'rotate('+i*360/set.length+' 160 160)'},g);e.textContent=ch;els.push(e)});return els}
const V='அ ஆ இ ஈ உ ஊ எ ஏ ஐ ஒ ஓ ஔ ஃ'.split(' '),C='க ங ச ஞ ட ண த ந ப ம ய ர ல வ ழ ள ற ன'.split(' '),SG=['','ா','ி','ீ','ு','ூ','ெ','ே','ை','ொ','ோ','ௌ'];
[103,70].forEach(r=>mk('circle',{cx:160,cy:160,r:r,fill:'none',stroke:'#38BDF8','stroke-opacity':.18},osvg));
mk('circle',{cx:160,cy:160,r:137,fill:'none',stroke:'#38BDF8','stroke-opacity':.3,'stroke-dasharray':'1 5'},osvg);
const rim=mk('g',{class:'sp rim',style:'--t:200s'},osvg),rs=mk('g',{class:'rim-scale'},rim);
for(let i=0;i<36;i++){const a=i*10*Math.PI/180,b=(i*10+5)*Math.PI/180;mk('circle',{cx:160+152*Math.sin(a),cy:160-152*Math.cos(a),r:2,fill:'#38BDF8',opacity:.85},rs);mk('circle',{cx:160+146*Math.sin(b),cy:160-146*Math.cos(b),r:1.1,fill:'#2DD4BF',opacity:.7},rs)}
const cE=oring(119,17,C,'#38BDF8',.9,'ccw c-ring',80),vE=oring(88,17,V,'#2DD4BF',.95,'v-ring',110);
let otm=0,olast=[];
function orbMode(k){clearInterval(otm);vE[12].classList.remove('lit','pz');olast.forEach(e=>e.classList.remove('lit'));olast=[];uyEl.textContent='';stage.classList.remove('s-listen','s-think','s-speak','s-mute','s-type','s-error');if(k!=='idle')stage.classList.add('s-'+k);if(k==='think')vE[12].classList.add('lit','pz');if(k==='speak')otm=setInterval(()=>{olast.forEach(e=>e.classList.remove('lit'));const c=Math.floor(Math.random()*18),v=Math.floor(Math.random()*12);olast=[cE[c],vE[v]];olast.forEach(e=>e.classList.add('lit'));uyEl.textContent=C[c]+' + '+V[v]+' = '+C[c]+SG[v]},420)}
