
(function(){
var r=document.documentElement;var b=document.getElementById('theme');
var rm=window.matchMedia&&matchMedia('(prefers-reduced-motion: reduce)').matches;
function cur(){return r.getAttribute('data-theme')==='light'?'light':'dark';}
function paint(){if(b){b.textContent=cur()==='light'?'☾':'☀';b.setAttribute('aria-pressed',cur()==='light'?'true':'false');}
var m=document.querySelector('meta[name=theme-color]');if(m)m.setAttribute('content',cur()==='light'?'#FBF8F1':'#17130E');}
paint();
if(b)b.addEventListener('click',function(){var n=cur()==='light'?'dark':'light';if(n==='light')r.setAttribute('data-theme','light');else r.removeAttribute('data-theme');try{localStorage.setItem('reau-theme',n);}catch(e){}paint();});
// ---- troca de idioma sem recarregar: busca a página do outro idioma e troca só os textos (o vídeo em reprodução não é recriado)
var cache={};var KEEP=['alt','title','aria-label','placeholder','href','hreflang','lang','aria-current'];
function curLang(){return r.getAttribute('lang')==='en'?'en':'pt';}
function urlFor(l){return location.pathname.replace(/\/(pt|en)\/([^\/]*)$/,'/'+l+'/$2');}
function attrs(a,b){KEEP.forEach(function(n){if(b.hasAttribute(n)){if(a.getAttribute(n)!==b.getAttribute(n))a.setAttribute(n,b.getAttribute(n));}else if(a.hasAttribute(n)){a.removeAttribute(n);}});}
function sig(e){if(e.nodeType!==1)return '#'+e.nodeType;return e.tagName+'|'+(e.id||'')+'|'+[].filter.call(e.classList,function(c){return c!=='on'&&c!=='sl'&&c!=='sr';}).join('.');}
function kids(n){return [].slice.call(n.childNodes);}
function align(A,B){var n=A.length,m=B.length,L=[],i,j;for(i=0;i<=n;i++){L.push([]);for(j=0;j<=m;j++)L[i].push(0);}
for(i=n-1;i>=0;i--)for(j=m-1;j>=0;j--)L[i][j]=sig(A[i])===sig(B[j])?L[i+1][j+1]+1:Math.max(L[i+1][j],L[i][j+1]);
var out=[];i=0;j=0;while(i<n&&j<m){if(sig(A[i])===sig(B[j])){out.push([A[i],B[j]]);i++;j++;}else if(L[i+1][j]>=L[i][j+1]){out.push([A[i],null]);i++;}else{out.push([null,B[j]]);j++;}}
while(i<n)out.push([A[i++],null]);while(j<m)out.push([null,B[j++]]);return out;}
function sync(a,b){
if(a.nodeType===3){if(a.nodeValue!==b.nodeValue)a.nodeValue=b.nodeValue;return;}
if(a.nodeType!==1)return;
attrs(a,b);if(a.tagName==='VIDEO')return;
var ops=align(kids(a),kids(b)),last=null;
ops.forEach(function(p){
if(p[0]&&p[1]){sync(p[0],p[1]);last=p[0];}
else if(p[0]){a.removeChild(p[0]);}
else{var c=document.importNode(p[1],true);a.insertBefore(c,last?last.nextSibling:a.firstChild);last=c;}});}
function head(d){r.setAttribute('lang',d.documentElement.getAttribute('lang'));document.title=d.title;
['meta[name=description]','meta[property="og:title"]','meta[property="og:description"]','meta[property="og:url"]'].forEach(function(s){var x=document.querySelector(s),y=d.querySelector(s);if(x&&y)x.setAttribute('content',y.getAttribute('content'));});
var xc=document.querySelector('link[rel=canonical]'),yc=d.querySelector('link[rel=canonical]');if(xc&&yc)xc.setAttribute('href',yc.getAttribute('href'));
var xa=document.querySelectorAll('link[rel=alternate][hreflang]'),ya=d.querySelectorAll('link[rel=alternate][hreflang]');
for(var i=0;i<xa.length&&i<ya.length;i++){xa[i].setAttribute('hreflang',ya[i].getAttribute('hreflang'));xa[i].setAttribute('href',ya[i].getAttribute('href'));}}
function tracks(l){[].forEach.call(document.querySelectorAll('video'),function(v){var on=[].some.call(v.textTracks,function(t){return t.mode==='showing';});
[].forEach.call(v.textTracks,function(t){var m=(t.language||'').slice(0,2)===l;if(on)t.mode=m?'showing':'disabled';});
[].forEach.call(v.querySelectorAll('track'),function(t){if((t.getAttribute('srclang')||'')===l)t.setAttribute('default','');else t.removeAttribute('default');});});}
function apply(d,l,push,url){
var end=(document.documentElement.scrollHeight-window.innerHeight-window.scrollY)<4;
var anc=null,off=0,cand=document.querySelectorAll('main h1,main h2,main h3,main p,main li,main figure,main tr,main article');
var best=1e9;for(var k=0;k<cand.length;k++){var rc=cand[k].getBoundingClientRect();if(rc.top>=0&&rc.top<window.innerHeight&&rc.top<best){best=rc.top;anc=cand[k];off=rc.top;}}
['a.skip','header.top','main','footer.foot'].forEach(function(s){var x=document.querySelector(s),y=d.querySelector(s);if(x&&y)sync(x,y);});
head(d);paint();tracks(l);
if(push)history.pushState({lang:l},'',url+location.hash);
document.dispatchEvent(new Event('reau:lang'));
if(end){window.scrollTo({top:document.documentElement.scrollHeight,behavior:'instant'});}else if(anc&&document.body.contains(anc)){var dy=anc.getBoundingClientRect().top-off;if(Math.abs(dy)>1)window.scrollTo({top:window.scrollY+dy,behavior:'instant'});}}
function switchTo(l,push){
if(l===curLang())return;
try{localStorage.setItem('reau-lang',l);}catch(e){}
var url=urlFor(l);
var go=function(txt){var d=new DOMParser().parseFromString(txt,'text/html');if(!d.querySelector('main')){location.href=url+location.hash;return;}cache[url]=txt;apply(d,l,push,url);};
if(cache[url]){go(cache[url]);return;}
fetch(url,{credentials:'same-origin'}).then(function(x){if(!x.ok)throw 0;return x.text();}).then(go).catch(function(){location.href=url+location.hash;});}
document.addEventListener('click',function(e){var a=e.target.closest&&e.target.closest('.langs a');if(!a||e.defaultPrevented||e.button||e.metaKey||e.ctrlKey||e.shiftKey||e.altKey||!window.fetch||!window.DOMParser)return;
e.preventDefault();switchTo((a.getAttribute('hreflang')||'').slice(0,2)==='en'?'en':'pt',true);});
window.addEventListener('popstate',function(){var l=/\/en\/[^\/]*$/.test(location.pathname)?'en':'pt';if(l!==curLang())switchTo(l,false);});
document.addEventListener('click',function(e){var c=e.target.closest&&e.target.closest('.chap button');if(!c)return;e.preventDefault();var v=c.closest('.vid').querySelector('video');var tt=parseFloat(c.getAttribute('data-t'));
var go=function(){v.currentTime=tt;v.play();};if(v.readyState>=1){go();}else{v.addEventListener('loadedmetadata',go,{once:true});v.preload='auto';v.load();}
v.scrollIntoView({behavior:rm?'auto':'smooth',block:'nearest'});});
[].forEach.call(document.querySelectorAll('.tw'),function(w){var s=w.querySelector('.tws');if(!s)return;
function u(){var m=s.scrollWidth-s.clientWidth;w.classList.toggle('sl',s.scrollLeft>1);w.classList.toggle('sr',m>1&&s.scrollLeft<m-1);}
u();s.addEventListener('scroll',u,{passive:true});window.addEventListener('resize',u);
if(window.ResizeObserver){var ro=new ResizeObserver(u);ro.observe(s);if(s.firstElementChild)ro.observe(s.firstElementChild);}});
document.addEventListener('play',function(e){if(e.target.tagName!=='VIDEO')return;[].forEach.call(document.querySelectorAll('video'),function(o){if(o!==e.target&&!o.paused)o.pause();});},true);
// ---- ampliar imagem do FAQ
document.addEventListener('click',function(e){var a=e.target.closest&&e.target.closest('a.zoom');if(!a||e.defaultPrevented||e.button||e.metaKey||e.ctrlKey||e.shiftKey||e.altKey)return;e.preventDefault();
var o=document.createElement('div');o.className='lb';o.setAttribute('role','dialog');o.setAttribute('aria-modal','true');var im=document.createElement('img');im.src=a.href;var al=a.querySelector('img');im.alt=al?al.alt:'';o.appendChild(im);
var last=document.activeElement;function close(){o.remove();document.removeEventListener('keydown',kd);if(last&&last.focus)last.focus();}
function kd(ev){if(ev.key==='Escape')close();}o.addEventListener('click',close);document.addEventListener('keydown',kd);document.body.appendChild(o);});
// ---- voltar ao topo
var up=document.createElement('button');up.id='up';up.type='button';up.textContent='↑';document.body.appendChild(up);
function upLabel(){var t=curLang()==='en'?'Back to top':'Voltar ao topo';up.setAttribute('aria-label',t);up.title=t;}upLabel();
function upShow(){up.classList.toggle('on',window.scrollY>900);}upShow();window.addEventListener('scroll',upShow,{passive:true});
up.addEventListener('click',function(){window.scrollTo({top:0,behavior:rm?'auto':'smooth'});});
document.addEventListener('reau:lang',upLabel);
})();
