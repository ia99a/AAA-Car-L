// الخريطة الحية خلف الشاشة الرئيسية. التطبيق يرسل الموقع والمخاطر عبر setPos و setPois.
// ?mini=1 خريطة مصغّرة داخل بطاقة (بلا إزاحة ولا تفاعل)، و ?style=light لطابع فاتح ملوّن.
var MINI = /[?&]mini=1/.test(location.search);
var LIGHT = /[?&]style=light/.test(location.search);
if (MINI) document.documentElement.className = 'mini' + (LIGHT ? ' light' : '');
var map = L.map('map', {zoomControl:false, attributionControl:true, zoomSnap:0.5, inertia:true,
  dragging:!MINI, touchZoom:!MINI, doubleClickZoom:!MINI, scrollWheelZoom:!MINI, boxZoom:!MINI, keyboard:!MINI})
  .setView([24.7136, 46.6753], MINI ? 15 : 16);
map.attributionControl.setPrefix(false);

// مصادر الخريطة بالترتيب: إن فشل الأول تمامًا ننتقل للتالي.
// حالة التحميل تُبلَّغ للتطبيق عبر عنوان الصفحة فقط (بلا أي جسر بين الصفحة والتطبيق).
var PROVIDERS = LIGHT ? [
  {url:'https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png',
   o:{subdomains:'abcd', maxZoom:19, attribution:'&copy; OpenStreetMap &copy; CARTO'}},
  {url:'https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}',
   o:{maxZoom:19, attribution:'&copy; Esri'}}
] : [
  {url:'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png',
   o:{subdomains:'abcd', maxZoom:19, attribution:'&copy; OpenStreetMap &copy; CARTO'}},
  {url:'https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/{z}/{y}/{x}',
   o:{maxNativeZoom:16, maxZoom:19, attribution:'&copy; Esri'}}
];
var tiles = null, provider = 0, loaded = 0, failed = 0;
function useProvider(i){
  if (tiles) map.removeLayer(tiles);
  provider = i; loaded = 0; failed = 0;
  tiles = L.tileLayer(PROVIDERS[i].url, PROVIDERS[i].o).addTo(map);
  tiles.on('tileload', function(){ if (++loaded === 1) document.title = 'tiles:ok:' + i; });
  tiles.on('tileerror', function(){
    if (loaded > 0 || ++failed < 6) return;
    if (provider + 1 < PROVIDERS.length) useProvider(provider + 1); else document.title = 'tiles:fail:' + Date.now();
  });
}
useProvider(0);
window.retryTiles = function(){ document.title = 'tiles:retry'; useProvider(0); };
window.addEventListener('resize', function(){ map.invalidateSize(); });

// الخريطة مزاحة لتظهر سيارتك في الجزء الظاهر بين البطاقات، لا تحت الشريط
var OFFSET_X = MINI ? 0 : 0.18, OFFSET_Y = MINI ? 0 : 0.10;

var meIcon = L.divIcon({className:'', iconSize:[30,30], iconAnchor:[15,15],
  html:'<svg class="me" id="meArrow" viewBox="0 0 30 30"><circle cx="15" cy="15" r="14" fill="rgba(59,125,221,.22)"/>'+
       '<path d="M15 4 L23 24 L15 19.5 L7 24 Z" fill="#e4e6ea" stroke="#16181c" stroke-width="1.5" stroke-linejoin="round"/></svg>'});
var me = null, follow = true, followTimer = null;

map.on('dragstart zoomstart', function(e){
  if (!e.hard) { follow = false; clearTimeout(followTimer); followTimer = setTimeout(function(){ follow = true; }, 10000); }
});

function centerOn(ll){
  var size = map.getSize();
  var p = map.project(ll, map.getZoom()).subtract([size.x*OFFSET_X, size.y*OFFSET_Y]);
  map.panTo(map.unproject(p, map.getZoom()), {animate:true, duration:.8});
}

window.setPos = function(lat, lng, bearing){
  map.invalidateSize();
  var ll = L.latLng(lat, lng);
  if (!me) me = L.marker(ll, {icon:meIcon, interactive:false, zIndexOffset:1000}).addTo(map);
  else me.setLatLng(ll);
  var a = document.getElementById('meArrow');
  if (a && bearing >= 0) a.style.transform = 'rotate(' + bearing + 'deg)';
  if (follow) centerOn(ll);
};

var CAM = '<svg viewBox="0 0 24 24"><path d="M17 10.5V7a1 1 0 0 0-1-1H4a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-3.5l4 4v-11z"/></svg>';
var BUMP = '<svg viewBox="0 0 24 24"><path d="M2 18h20v2H2zM4 17c0-4.4 3.6-8 8-8s8 3.6 8 8z"/></svg>';
var poiLayer = L.layerGroup().addTo(map);

// pois: [[lat, lng, kind], ...] حيث kind = 0 كاميرا، 1 مطب
window.setPois = function(pois){
  poiLayer.clearLayers();
  for (var i = 0; i < pois.length; i++) {
    var p = pois[i], cam = p[2] === 0;
    L.marker([p[0], p[1]], {interactive:false, icon:L.divIcon({className:'', iconSize:[22,22], iconAnchor:[11,11],
      html:'<div class="hz ' + (cam ? 'cam' : 'bump') + '">' + (cam ? CAM : BUMP) + '</div>'})}).addTo(poiLayer);
  }
};
