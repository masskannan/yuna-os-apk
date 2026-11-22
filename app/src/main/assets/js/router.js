'use strict';
/* overlays, drawer, menu, single back handler. Settings/permissions are Pg pages (batches 4-5). */
const O={drawer:()=>$('#drawer').classList.contains('on'),menu:()=>$('#moreMenu').classList.contains('on'),att:()=>$('#attachSheet').classList.contains('on'),hist:()=>$('#historyPage').classList.contains('on')};
function scrim(){$('#scrim').classList.toggle('on',O.drawer()||O.att())}
function openDrawer(){$('#drawer').classList.add('on');scrim()}function closeDrawer(){$('#drawer').classList.remove('on');scrim()}
$('#bMenu').onclick=openDrawer;$('#scrim').onclick=()=>{closeDrawer();$('#attachSheet').classList.remove('on');scrim()};
$('#bMore').onclick=()=>$('#moreMenu').classList.toggle('on');
$('#attach').onclick=()=>{$('#attachSheet').classList.add('on');scrim()};
const closeMore=()=>$('#moreMenu').classList.remove('on');
$('#mSet').onclick=()=>{closeMore();Pg.open('settings')};
$('#mPerm').onclick=()=>{closeMore();Pg.open('permissions')};
$('#mNew').onclick=()=>{closeMore();if(sending&&abort)abort.abort();stopSpeakUI();closeFind();save();cur={id:Date.now(),title:'',msgs:[]};showHello()};
$$('[data-back]').forEach(b=>b.onclick=()=>window.handleYunaBackPress());
const GO={projects:'projects',code:'code',creations:'artifacts',memory:'memory'};
$$('[data-go]').forEach(a=>a.onclick=()=>{closeDrawer();Pg.open(GO[a.dataset.go])});
/* single back handler: dialog → type sheet → attach → menu → page stack → history → drawer → live */
window.handleYunaBackPress=function(){
 if(Out.closeBox())return true;
 if(UI.closeDlg())return true;
 if(closeFind())return true;
 if(Live.typing){closeType();return true}
 if(O.att()){$('#attachSheet').classList.remove('on');scrim();return true}
 if(O.menu()){closeMore();return true}
 if(Pg.back())return true;
 if(O.hist()){$('#historyPage').classList.remove('on');return true}
 if(O.drawer()){closeDrawer();return true}
 if(Live.on&&$('#live').classList.contains('on')){liveEnd();return true}
 return false};
$('#mShare').textContent=T('உரையாடலைப் பகிர்','Share chat');
$('#mShare').onclick=()=>{closeMore();const x=cur.msgs.map(m=>(m.r==='u'?T('நான்: ','Me: '):'YUNA: ')+m.t).join('\n\n');if(!x){toast(t('histEmpty'));return}navigator.share?navigator.share({text:x}).catch(()=>{}):copyText(x)};
