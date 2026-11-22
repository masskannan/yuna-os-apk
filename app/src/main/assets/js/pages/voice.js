'use strict';
/* Page 07: voice input (composer mic) + native callbacks. Rules T1/T2: partial REPLACES, result appends. */
function setRec(v){recording=v;document.documentElement.classList.toggle('rec',v);$('#dock').classList.toggle('rec',v)}
function startMic(){if(recording)return;
 if(B()&&permAll().mic===false){toast(T('மைக் அனுமதி இல்லை','Microphone permission is off'),()=>call('openPermissionSettings','mic'),T('இயக்கு','Grant'));return}
partialBase=inp.value;setRec(true);const c=ls.g('yuna_cloud_url');
 if(call('isExternalCallActive')!==true)call('startBluetoothMic');
 call(c?'startCloudWhisper':'startVoice',c||undefined);if(!B()&&/demo/.test(location.search))demoMic()}
function stopMic(keep){setRec(false);call('stopCloudWhisper');call('stopVoice')}
function abortMic(){setRec(false);inp.placeholder=t('ph');call('cancelCloudWhisper');toast(t('micOff'),startMic)}
$('#mic').onclick=startMic;
$('#bStop').onclick=()=>{call('finishVoiceCapture');stopMic()};
$('#bX').onclick=()=>{const rec=inp.value.slice(partialBase.length);const go=()=>{inp.value=partialBase;stopMic();grow()};if(rec.length>20)UI.ask(t('cancel'),go,t('stop'));else go()};
function voicePartial(x){x=x||'';if(Live.on){liveUserPartial(x);return}
 if(composing)return;inp.value=partialBase+(partialBase&&x?' ':'')+x;grow();inp.scrollTop=inp.scrollHeight}
window.onYunaVoicePartial=voicePartial;
window.onYunaWhisperChunk=voicePartial;
window.onYunaVoiceResult=function(x){x=(x||'').trim();if(!x)return;
 if(Live.on){liveUser(x);return}
 inp.value=(partialBase?partialBase+' ':'')+x;partialBase='';grow();YUNA.voiceTurn=true;setRec(false);setTimeout(()=>{if(!composing)send()},120)};
window.onYunaVoiceEnded=function(){setRec(false)};
window.onYunaMicAborted=abortMic;
window.onYunaProcessText=function(x){if(!x)return;inp.value=String(x);grow();inp.focus()};
const WB=32,lv=Array(WB).fill(0),wv=$('#wave');for(let i=0;i<WB;i++)wv.appendChild(document.createElement('i'));
window.onYunaMicLevel=function(l){lv.push(Math.min(1,Math.max(0,+l||0)));lv.shift();[...wv.children].forEach((e,i)=>e.style.height=(4+lv[i]*38)+'px')};
window.onYunaTtsDone=function(){Yuna.sayDone();liveTurnEnd()};
window.onYunaAppResumed=function(){kbUpdate();YUNA.permRefresh&&YUNA.permRefresh()};
function demoMic(){let n=0;const iv=setInterval(()=>{if(!recording)return clearInterval(iv);window.onYunaMicLevel(Math.random());if(++n%10===0)voicePartial('சோதனை உரை')},100)}
