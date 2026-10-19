
(function(){
function norm(s){return s.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g,'').replace(/[‐-―]/g,'-');}
var q=document.getElementById('q');var secs=[].slice.call(document.querySelectorAll('section.faqsec'));var none=document.getElementById('none');var toc=document.querySelector('.toc');
var SEL='.qa, :scope > .tw tbody tr';var items=[].slice.call(document.querySelectorAll('.qa, .faqsec > .tw tbody tr'));items.forEach(function(e){e._t=norm(e.textContent);});
var tl={};if(toc)[].forEach.call(toc.querySelectorAll('a'),function(a){tl[a.getAttribute('href').slice(1)]=a;});
function apply(){var t=norm(q.value.trim());var shown=0;items.forEach(function(e){var ok=!t||e._t.indexOf(t)>-1;e.hidden=!ok;if(ok)shown++;});
secs.forEach(function(s){var its=s.querySelectorAll(SEL);var any=!t||!its.length||[].some.call(its,function(e){return !e.hidden;});s.hidden=!any;if(tl[s.id])tl[s.id].parentNode.hidden=!any;});
if(none)none.hidden=!t||shown>0;}
if(q)q.addEventListener('input',apply);
document.addEventListener('reau:lang',function(){items.forEach(function(e){e._t=norm(e.textContent);});if(q)apply();});
if(!toc||!('IntersectionObserver' in window))return;
var cur=null;function mark(id){if(cur===id||!tl[id])return;if(cur&&tl[cur])tl[cur].classList.remove('on');cur=id;var a=tl[id];a.classList.add('on');
var top=a.offsetTop,h=a.offsetHeight;if(top<toc.scrollTop+10)toc.scrollTop=top-10;else if(top+h>toc.scrollTop+toc.clientHeight-10)toc.scrollTop=top+h-toc.clientHeight+10;}
var vis={};var io=new IntersectionObserver(function(es){es.forEach(function(e){vis[e.target.id]=e.isIntersecting;});
var first=null;secs.forEach(function(s){if(!first&&vis[s.id]&&!s.hidden)first=s.id;});if(first)mark(first);},{rootMargin:'-110px 0px -60% 0px',threshold:0});
secs.forEach(function(s){io.observe(s);});
toc.addEventListener('click',function(e){var a=e.target.closest('a');if(a)mark(a.getAttribute('href').slice(1));});
mark(secs[0].id);
})();
