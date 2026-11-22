'use strict';
/* Pages 05-06: Live orb + Live transcript. Rules L1-L11 (one user line + one YUNA line, barge-in, single save per turn). */
const Live={on:false,muted:false,prevMuted:false,typing:false,tts:0,idle:0,t0:0,clk:0,turn:null,done:false,sid:0,ctrl:null,pend:0};
let liveState='LISTEN';
function setLiveLabel(s){const w=t(s);$('#orbSub').textContent=w;$('#liveStateLabel').textContent='• '+w;$('#glPillText').textContent=Live.on?w:t('live');$('#liveMiniPill').textContent='◉ '+w}
function liveSet(s){liveState=s;orbMode({LISTEN:'listen',THINK:'think',SEARCH:'think',READ:'think',WRITE:'think',SPEAK:'speak',MUTE:'mute',TYPE:'type',ERROR:'error'}[s]||'idle');setLiveLabel(s)}
function liveListen(){if(!Live.on)return;liveSet(Live.muted?'MUTE':'LISTEN')}
/* L6: each finished/cut turn saved exactly once into a "Live Chat" session */
function liveSave(){const tr=Live.turn;if(!tr||tr.saved||!tr.u)return;tr.saved=true;
 let s=sessions.find(x=>x.id===Live.sid);if(!s){s={id:Live.sid,title:T('நேரலை உரையாடல்','Live Chat'),live:1,msgs:[]};sessions.unshift(s)}
 s.msgs.push({r:'u',t:tr.u});if(tr.y)s.msgs.push({r:'y',t:tr.y});persistSessions();renderHist()}
function fadeClear(){const b=$('#liveTranscriptBox');b.style.opacity=0;setTimeout(()=>{$('.lt-y').textContent='';$('.lt-u').textContent='';b.style.opacity=1},200)}
function liveStart(){if(Live.on)return;stopSpeakUI();Object.assign(Live,{on:true,t0:Date.now(),sid:Date.now(),turn:null,done:false,tts:0,typing:false,muted:false,idle:Date.now()});
 $('#lMute').textContent='🎙';$('#live').classList.add('on');$('#liveMiniPill').style.display='none';call('startLiveSession');liveSet('LISTEN');
 clearInterval(Live.clk);Live.clk=setInterval(()=>{const s=Math.floor((Date.now()-Live.t0)/1000);$('#liveTime').textContent=Math.floor(s/60)+':'+String(s%60).padStart(2,'0');
  if(call('isExternalCallActive')===true){liveCut();Live.turn=null;Live.done=false;$('.lt-u').textContent='';$('.lt-y').textContent=''}  /* L11 */
  idleCheck()},1000)}
function liveEnd(){if(!Live.on)return;liveCut();liveSave();Live.ctrl&&Live.ctrl.abort();Live.on=false;Live.typing=false;Live.tts=0;clearInterval(Live.clk);
 call('stopLiveSession');call('stopTts');$('#live').classList.remove('on');$('#liveMiniPill').style.display='none';$('#typeSheet').classList.remove('on');$('.lt-u').textContent='';$('.lt-y').textContent='';orbMode('idle')}
function idleCheck(){if(Live.tts||Live.typing||Live.pend)Live.idle=Date.now();else if(Date.now()-Live.idle>45000)liveEnd()}
/* L2-L4: new partial replaces previous pair; interrupting YUNA saves her line with … */
function liveUserPartial(x){if(!Live.on)return;x=(x||'').trim();if(!x)return;Live.idle=Date.now();
 if(Live.tts&&x.length>1)liveCut();
 if(Live.ctrl&&Live.pend){Live.ctrl.abort();Live.pend=0}
 if(Live.done){fadeClear();Live.done=false;setTimeout(()=>{$('.lt-u').textContent=x},210)}else $('.lt-u').textContent=x;
 if(liveState!=='LISTEN'&&!Live.muted)liveSet('LISTEN')}
async function liveUser(x){if(!Live.on)return;Live.idle=Date.now();x=(x||'').trim();
 if(x.length<2){liveListen();return}                       /* L5 noise */
 if(Live.done){fadeClear();Live.done=false;setTimeout(()=>{$('.lt-u').textContent=x},210)}else $('.lt-u').textContent=x;
 Live.turn={u:x,y:'',saved:false};liveSet('THINK');const c=Live.ctrl=new AbortController();Live.pend=1;
 try{const d=await Yuna.ask(x,[],c,Yuna.sid()+'-L'+Live.sid,true);if(c!==Live.ctrl||!Live.on)return;Live.pend=0;Live.turn.y=d.text;Act.run(d.actions,()=>{});liveSay(d.text)}
 catch(e){if(c!==Live.ctrl)return;Live.pend=0;if(e.name==='AbortError')return;liveSet('ERROR');Live.turn=null;toast(e.message==='nourl'?t('noUrl'):t('noNet'),()=>liveUser(x))} }  /* L10 */
function liveSay(a){const id=++Live.tts;$('.lt-y').textContent=a;liveSet('SPEAK');Yuna.tts(a);
 setTimeout(()=>{if(Live.tts===id)liveTurnEnd()},Math.min(60000,2500+a.length*90))}   /* safety if native TTS-done never arrives / persona SILENT */
function liveTurnEnd(){if(!Live.on||!Live.tts)return;Live.tts=0;Live.done=true;liveSave();
 setTimeout(()=>{if(Live.on&&!Live.tts&&!Live.muted&&!Live.typing){liveSet('LISTEN');call('startCloudWhisper',ls.g('yuna_cloud_url')||undefined)}},350)}
function liveCut(){if(!Live.tts)return;Live.tts=0;call('bargeInLive');call('stopTts');
 if(Live.turn){Live.turn.y=($('.lt-y').textContent||Live.turn.y).replace(/…?$/,'')+' …'}Live.done=true;liveSave()}
$('#bLive').onclick=liveStart;$('#lEnd').onclick=liveEnd;
$('#lMute').onclick=()=>{liveCut();Live.muted=!Live.muted;call('setLiveMuted',Live.muted);liveSet(Live.muted?'MUTE':'LISTEN');$('#lMute').textContent=Live.muted?'🔇':'🎙'};
/* L7: type sheet mutes the mic, and restores the PREVIOUS mute state on close */
$('#lType').onclick=()=>{Live.prevMuted=Live.muted;Live.typing=true;if(!Live.muted){Live.muted=true;call('setLiveMuted',true)}$('#typeSheet').classList.add('on');$('#typeIn').focus();liveSet('TYPE')};
function closeType(){Live.typing=false;$('#typeSheet').classList.remove('on');if(Live.muted!==Live.prevMuted){Live.muted=Live.prevMuted;call('setLiveMuted',Live.muted)}$('#lMute').textContent=Live.muted?'🔇':'🎙';liveListen()}
$('#typeIn').addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.isComposing&&e.target.value.trim()){const v=e.target.value;e.target.value='';liveCut();closeType();liveUser(v)}});
$('#bMin').onclick=()=>{$('#live').classList.remove('on');$('#liveMiniPill').style.display='block'};
$('#liveMiniPill').onclick=()=>{$('#live').classList.add('on');$('#liveMiniPill').style.display='none'};
