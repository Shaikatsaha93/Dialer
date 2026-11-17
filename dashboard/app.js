// Dialer Dispatch: live map and rider management for delivery companies.
// Same Firebase project as the app. Who may see what is enforced by firestore.rules and
// database.rules.json, not by this page:
//  - the main admin (ADMIN_EMAIL) creates companies and sees everything,
//  - a company admin (companies/{code}.adminUids) sees and manages only that company's riders.
import { initializeApp, deleteApp } from 'https://www.gstatic.com/firebasejs/10.14.1/firebase-app.js';
import {
  getAuth, onAuthStateChanged, signInWithEmailAndPassword, signOut,
  sendPasswordResetEmail, createUserWithEmailAndPassword, connectAuthEmulator
} from 'https://www.gstatic.com/firebasejs/10.14.1/firebase-auth.js';
import {
  getFirestore, collection, doc, query, where, onSnapshot, getDoc, setDoc, updateDoc,
  deleteDoc, arrayUnion, Timestamp, serverTimestamp, connectFirestoreEmulator
} from 'https://www.gstatic.com/firebasejs/10.14.1/firebase-firestore.js';
import {
  getDatabase, ref, onValue, off, set, remove, get, query as dbQuery, orderByKey, endAt
} from 'https://www.gstatic.com/firebasejs/10.14.1/firebase-database.js';

const firebaseConfig = {
  apiKey: 'AIzaSyD2dyTMFtr-0aKqo_4VnVbuOnVjk4deAvY',
  authDomain: 'fir-7eb9d.firebaseapp.com',
  projectId: 'fir-7eb9d',
  databaseURL: 'https://fir-7eb9d.firebaseio.com'
};
const ADMIN_EMAIL = 'shaikatsaha93@gmail.com';
const DAY = 24 * 60 * 60 * 1000;
/** A position older than this is shown orange: the phone stopped sending (no network, app killed) */
const STALE_MS = 5 * 60 * 1000;
/** Routes are kept this many days; older days are deleted when a rider's route is opened */
const KEEP_DAYS = 30;
const FAR_FUTURE = new Date('2099-12-31T00:00:00Z');

// Local testing against the Firebase emulators: http://localhost:…/?emulator
const EMULATOR = ['localhost', '127.0.0.1'].includes(location.hostname) && new URLSearchParams(location.search).has('emulator');
if (EMULATOR) {
  firebaseConfig.projectId = 'demo-dialer';
  firebaseConfig.databaseURL = 'http://127.0.0.1:9005?ns=demo-dialer-default-rtdb';
}

const app = initializeApp(firebaseConfig);
const auth = getAuth(app);
const fs = getFirestore(app);
const db = getDatabase(app);
if (EMULATOR) {
  connectAuthEmulator(auth, 'http://127.0.0.1:9099', { disableWarnings: true });
  connectFirestoreEmulator(fs, '127.0.0.1', 8085);
}

const $ = (id) => document.getElementById(id);
const state = {
  user: null,
  isAdmin: false,
  companies: new Map(),     // code -> data
  company: null,            // selected code
  live: {},                 // uid -> live position
  riders: new Map(),        // uid -> devices record
  selectedRider: null,
  unsub: [],                // listeners of the selected company
  unsubCompanies: null
};

// ---------- helpers ----------

function el(tag, attrs = {}, ...children) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === 'class') e.className = v;
    else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else if (v !== undefined && v !== null) e.setAttribute(k, v);
  }
  for (const c of children.flat()) {
    if (c === null || c === undefined || c === false) continue;
    e.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return e;
}

function ago(ms) {
  if (!ms) return 'never';
  const s = Math.max(0, Math.round((Date.now() - ms) / 1000));
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  if (s < 86400) return `${Math.round(s / 3600)} h ago`;
  return new Date(ms).toLocaleDateString();
}

function dateText(ms) {
  if (!ms) return '';
  if (ms >= FAR_FUTURE.getTime() - DAY) return 'no limit';
  return new Date(ms).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
}

function dayKey(d) {
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

function initials(name) {
  return (name || '?').trim().split(/\s+/).slice(0, 2).map((w) => w[0]).join('').toUpperCase() || '?';
}

function liveClass(p) {
  if (!p || !p.onDuty) return 'off';
  return Date.now() - (p.ts || 0) > STALE_MS ? 'stale' : 'live';
}

function showBanner(text) {
  $('banner').textContent = text || '';
  $('banner').classList.toggle('hidden', !text);
}

function toMillis(ts) {
  return ts && typeof ts.toMillis === 'function' ? ts.toMillis() : null;
}

function riderStatus(r) {
  const exp = toMillis(r.expiresAt);
  if (r.approved && exp && exp < Date.now()) return 'expired';
  if (r.approved) return 'active';
  return r.approvedAt ? 'blocked' : 'pending';
}

// ---------- sign-in ----------

onAuthStateChanged(auth, async (user) => {
  stopCompany();
  // Another account may see other companies: choose again
  state.company = null;
  state.companies = new Map();
  if (state.unsubCompanies) { state.unsubCompanies(); state.unsubCompanies = null; }
  state.user = user;
  // Riders sign in anonymously in the app; only email/password accounts use the dashboard
  if (!user || user.isAnonymous) {
    $('app').classList.add('hidden');
    $('login').classList.remove('hidden');
    return;
  }
  state.isAdmin = user.email?.toLowerCase() === ADMIN_EMAIL &&
    user.providerData.some((p) => p.providerId === 'password');
  $('user-email').textContent = user.email || '';
  document.querySelectorAll('.super-only').forEach((e) => e.classList.toggle('hidden', !state.isAdmin));
  $('login').classList.add('hidden');
  $('app').classList.remove('hidden');
  watchCompanies();
});

$('login-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  $('login-error').textContent = '';
  try {
    await signInWithEmailAndPassword(auth, $('login-email').value.trim(), $('login-password').value);
  } catch (err) {
    $('login-error').textContent = err.code === 'auth/invalid-credential' ? 'Wrong email or password' : err.message;
  }
});

$('forgot').addEventListener('click', async () => {
  const email = $('login-email').value.trim();
  if (!email) { $('login-error').textContent = 'Enter your email first'; return; }
  try {
    await sendPasswordResetEmail(auth, email);
    $('login-error').textContent = 'A password reset email has been sent.';
  } catch (err) {
    $('login-error').textContent = err.message;
  }
});

$('sign-out').addEventListener('click', () => signOut(auth));

// ---------- companies ----------

function watchCompanies() {
  const q = state.isAdmin
    ? collection(fs, 'companies')
    : query(collection(fs, 'companies'), where('adminUids', 'array-contains', state.user.uid));
  state.unsubCompanies = onSnapshot(q, (snap) => {
    state.companies = new Map(snap.docs.map((d) => [d.id, d.data()]));
    renderCompanySelect();
    if (state.isAdmin) renderCompanies();
    if (!state.companies.size) {
      showBanner(state.isAdmin ? 'No companies yet: create one in the Companies tab.' : 'This account is not the admin of any company.');
      if (state.isAdmin) switchTab('companies');
    }
  }, (err) => showBanner('Cannot load companies: ' + err.message));
}

function renderCompanySelect() {
  const select = $('company-select');
  const saved = localStorageGet('company');
  select.replaceChildren(...[...state.companies.entries()]
    .sort((a, b) => (a[1].name || a[0]).localeCompare(b[1].name || b[0]))
    .map(([code, c]) => el('option', { value: code }, `${c.name || code} (${code})`)));
  select.classList.toggle('hidden', state.companies.size === 0);
  const pick = state.companies.has(state.company) ? state.company
    : state.companies.has(saved) ? saved
    : state.companies.keys().next().value;
  if (pick) {
    select.value = pick;
    if (pick !== state.company) selectCompany(pick);
  } else {
    stopCompany();
  }
  const c = state.companies.get(pick);
  const exp = c && toMillis(c.expiresAt);
  showBanner(exp && exp < Date.now() ? `The subscription of ${c.name || pick} has ended: its riders cannot use the app. Contact the admin to renew.` : '');
}

$('company-select').addEventListener('change', (e) => selectCompany(e.target.value));

function selectCompany(code) {
  stopCompany();
  state.company = code;
  localStorageSet('company', code);
  $('company-code').textContent = code;
  // Live positions
  const liveRef = ref(db, `live/${code}`);
  onValue(liveRef, (snap) => {
    state.live = snap.val() || {};
    renderMap();
  }, (err) => showBanner('Cannot load live positions: ' + err.message));
  state.unsub.push(() => off(liveRef));
  // Rider records
  const q = query(collection(fs, 'devices'), where('companyId', '==', code));
  state.unsub.push(onSnapshot(q, (snap) => {
    state.riders = new Map(snap.docs.map((d) => [d.id, d.data()]));
    renderRiders();
    renderMap();
  }, (err) => showBanner('Cannot load riders: ' + err.message)));
  firstFit = true;
}

function stopCompany() {
  state.unsub.forEach((u) => u());
  state.unsub = [];
  state.live = {};
  state.riders = new Map();
  state.selectedRider = null;
  clearRoute();
  if (map) { markers.forEach((m) => m.remove()); markers.clear(); }
}

function localStorageGet(k) { try { return localStorage.getItem('dispatch.' + k); } catch { return null; } }
function localStorageSet(k, v) { try { localStorage.setItem('dispatch.' + k, v); } catch { /* private mode */ } }

// ---------- tabs ----------

function switchTab(name) {
  document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t.dataset.tab === name));
  document.querySelectorAll('.tab-page').forEach((p) => p.classList.toggle('hidden', p.id !== `tab-${name}`));
  if (name === 'map' && map) setTimeout(() => map.invalidateSize(), 0);
}
document.querySelectorAll('.tab').forEach((t) => t.addEventListener('click', () => switchTab(t.dataset.tab)));

// ---------- map ----------

let map = null;
const markers = new Map();
let firstFit = true;
let routeLine = null;
let routeRef = null;

function ensureMap() {
  if (map) return;
  map = L.map('map', { zoomControl: true }).setView([23.8103, 90.4125], 12); // Dhaka
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
  }).addTo(map);
}

function riderName(uid, p) {
  return state.riders.get(uid)?.name || p?.name || 'Rider';
}

function popupContent(uid, p) {
  const r = state.riders.get(uid);
  const phone = r?.phone || p.phone || '';
  const speed = typeof p.speed === 'number' ? `${Math.round(p.speed * 3.6)} km/h` : null;
  const battery = typeof p.battery === 'number' ? `${p.battery}%${p.charging ? ' (charging)' : ''}` : null;
  return el('div', {},
    el('b', {}, riderName(uid, p)),
    el('div', {}, p.onDuty ? 'On duty' : 'Off duty', ' · updated ', ago(p.ts)),
    phone ? el('div', {}, el('a', { href: `tel:${phone}` }, phone)) : null,
    speed ? el('div', {}, 'Speed: ', speed) : null,
    battery ? el('div', {}, 'Battery: ', battery) : null,
    typeof p.accuracy === 'number' ? el('div', { class: 'muted' }, `Accuracy ±${Math.round(p.accuracy)} m`) : null,
    el('button', { class: 'link', onclick: () => selectRider(uid) }, "Show today's route")
  );
}

/** [cls] is live / stale / off; also kept as the icon's className to see when it changes */
function markerIcon(cls) {
  return L.divIcon({
    className: cls,
    html: `<div class="rider-marker ${cls}"></div>`,
    iconSize: [30, 30],
    iconAnchor: [15, 15]
  });
}

function renderMap() {
  ensureMap();
  const onlyDuty = $('only-duty').checked;
  const search = $('rider-search').value.trim().toLowerCase();
  const seen = new Set();
  const entries = Object.entries(state.live)
    .filter(([uid, p]) => typeof p?.lat === 'number' && typeof p?.lng === 'number')
    // A rider removed from the company no longer appears (its record is gone)
    .filter(([uid]) => state.riders.size === 0 || state.riders.has(uid));

  for (const [uid, p] of entries) {
    if (onlyDuty && !p.onDuty) continue;
    seen.add(uid);
    let m = markers.get(uid);
    if (!m) {
      m = L.marker([p.lat, p.lng]).addTo(map);
      // Bound once and built from the latest data, so a refresh does not close an open popup.
      // Text only (DOM nodes, never HTML strings): names are typed by riders.
      m.bindPopup(() => popupContent(uid, state.live[uid] || p));
      m.bindTooltip(el('span'), { direction: 'top', offset: [0, -14] });
      markers.set(uid, m);
    }
    m.setLatLng([p.lat, p.lng]);
    const cls = liveClass(p);
    if (m.options.icon?.options?.className !== cls) m.setIcon(markerIcon(cls));
    m.getElement()?.querySelector('.rider-marker')?.replaceChildren(document.createTextNode(initials(riderName(uid, p))));
    m.setTooltipContent(el('span', {}, riderName(uid, p)));
  }
  for (const [uid, m] of markers) {
    if (!seen.has(uid)) { m.remove(); markers.delete(uid); }
  }
  if (firstFit && seen.size) {
    firstFit = false;
    const bounds = L.latLngBounds([...seen].map((uid) => [state.live[uid].lat, state.live[uid].lng]));
    map.fitBounds(bounds.pad(0.3), { maxZoom: 15 });
  }
  renderList(entries.filter(([uid, p]) => (!onlyDuty || p.onDuty) &&
    (!search || riderName(uid, p).toLowerCase().includes(search) || (state.riders.get(uid)?.phone || '').includes(search))));
}

function renderList(entries) {
  const list = $('rider-list');
  const sorted = [...entries].sort((a, b) => {
    const order = { live: 0, stale: 1, off: 2 };
    return order[liveClass(a[1])] - order[liveClass(b[1])] || riderName(a[0], a[1]).localeCompare(riderName(b[0], b[1]));
  });
  if (!sorted.length) {
    list.replaceChildren(el('li', { class: 'muted' }, $('only-duty').checked ? 'No rider on duty right now.' : 'No rider has shared a location yet.'));
    return;
  }
  list.replaceChildren(...sorted.map(([uid, p]) => {
    const battery = typeof p.battery === 'number' ? ` · 🔋${p.battery}%` : '';
    return el('li', { class: uid === state.selectedRider ? 'selected' : '', onclick: () => selectRider(uid) },
      el('span', { class: `dot ${liveClass(p)}` }),
      el('div', {},
        el('div', { class: 'name' }, riderName(uid, p)),
        el('div', { class: 'meta' }, `${p.onDuty ? 'On duty' : 'Off duty'} · ${ago(p.ts)}${battery}`)
      )
    );
  }));
}

$('only-duty').addEventListener('change', renderMap);
$('rider-search').addEventListener('input', renderMap);
// Keeps "updated … ago" and the stale colours current
setInterval(() => { if (state.company) renderMap(); }, 15000);

function selectRider(uid) {
  state.selectedRider = uid;
  const p = state.live[uid];
  if (p) map.setView([p.lat, p.lng], Math.max(map.getZoom(), 15));
  markers.get(uid)?.openPopup();
  $('route-box').classList.remove('hidden');
  $('route-title').textContent = riderName(uid, p);
  if (!$('route-day').value) $('route-day').value = dayKey(new Date());
  showRoute();
  pruneOldDays(uid);
  renderMap();
}

function showRoute() {
  clearRoute(false);
  const uid = state.selectedRider;
  const day = $('route-day').value;
  if (!uid || !day) return;
  routeRef = ref(db, `tracks/${state.company}/${uid}/${day}`);
  onValue(routeRef, (snap) => {
    const points = Object.values(snap.val() || {})
      .filter((pt) => typeof pt.lat === 'number' && typeof pt.lng === 'number')
      .sort((a, b) => a.t - b.t);
    if (routeLine) routeLine.remove();
    routeLine = null;
    if (points.length < 2) {
      $('route-info').textContent = points.length ? 'Only one point on this day.' : 'No route on this day.';
      return;
    }
    routeLine = L.polyline(points.map((pt) => [pt.lat, pt.lng]), { color: '#007aff', weight: 4, opacity: 0.8 }).addTo(map);
    let km = 0;
    for (let i = 1; i < points.length; i++) km += map.distance([points[i - 1].lat, points[i - 1].lng], [points[i].lat, points[i].lng]) / 1000;
    const from = new Date(points[0].t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    const to = new Date(points[points.length - 1].t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    $('route-info').textContent = `${km.toFixed(1)} km · ${from}–${to} · ${points.length} points`;
  }, (err) => { $('route-info').textContent = 'Cannot load the route: ' + err.message; });
}

function clearRoute(hideBox = true) {
  if (routeRef) { off(routeRef); routeRef = null; }
  if (routeLine) { routeLine.remove(); routeLine = null; }
  if (hideBox) $('route-box').classList.add('hidden');
}

$('route-day').addEventListener('change', showRoute);
$('route-clear').addEventListener('click', () => { state.selectedRider = null; clearRoute(); renderMap(); });

/** Deletes the rider's routes older than KEEP_DAYS (no server code runs this otherwise) */
async function pruneOldDays(uid) {
  try {
    const cutoff = dayKey(new Date(Date.now() - KEEP_DAYS * DAY));
    const old = await get(dbQuery(ref(db, `tracks/${state.company}/${uid}`), orderByKey(), endAt(cutoff)));
    const days = Object.keys(old.val() || {}).filter((d) => d < cutoff);
    await Promise.all(days.map((d) => remove(ref(db, `tracks/${state.company}/${uid}/${d}`))));
  } catch (err) {
    console.warn('Prune failed', err);
  }
}

// ---------- riders ----------

function renderRiders() {
  const body = $('riders-body');
  const rows = [...state.riders.entries()].sort((a, b) => {
    const order = { pending: 0, active: 1, expired: 2, blocked: 3 };
    return order[riderStatus(a[1])] - order[riderStatus(b[1])] || (a[1].name || '').localeCompare(b[1].name || '');
  });
  const pending = rows.filter(([, r]) => riderStatus(r) === 'pending').length;
  $('pending-badge').textContent = pending;
  $('pending-badge').classList.toggle('hidden', !pending);
  if (!rows.length) {
    body.replaceChildren(el('tr', {}, el('td', { colspan: 6, class: 'muted' }, 'No riders yet. Give riders your company code.')));
    return;
  }
  body.replaceChildren(...rows.map(([uid, r]) => {
    const status = riderStatus(r);
    const exp = toMillis(r.expiresAt);
    const statusText = {
      pending: 'Waiting for approval',
      active: `Active until ${dateText(exp)}`,
      expired: `Expired ${dateText(exp)}`,
      blocked: 'Blocked'
    }[status];
    const days = el('select', {}, ...[30, 90, 365].map((d) => el('option', { value: d }, `${d} days`)));
    const actions = el('div', { class: 'row' });
    if (status === 'active') {
      actions.append(days, el('button', { onclick: () => approve(uid, r, +days.value, true) }, 'Extend'));
      actions.append(el('button', { class: 'danger', onclick: () => block(uid, r) }, 'Block'));
    } else {
      actions.append(days, el('button', { class: 'primary', onclick: () => approve(uid, r, +days.value, false) }, status === 'pending' ? 'Approve' : 'Renew'));
    }
    actions.append(el('button', { class: 'link danger', onclick: () => removeRider(uid, r) }, 'Delete'));
    return el('tr', {},
      el('td', {}, r.name || ''),
      el('td', {}, r.phone ? el('a', { href: `tel:${r.phone}` }, r.phone) : ''),
      el('td', { class: 'muted' }, r.device || ''),
      el('td', {}, el('span', { class: `status ${status}` }, statusText)),
      el('td', {}, el('button', { class: 'link', onclick: () => editSip(uid, r) }, r.sipUsername ? `${r.sipUsername}@${r.sipDomain || ''}` : 'Set up')),
      el('td', {}, actions)
    );
  }));
}

async function approve(uid, r, days, extend) {
  const now = Date.now();
  const current = toMillis(r.expiresAt);
  const base = extend && current && current > now ? current : now;
  const expires = new Date(base + days * DAY);
  try {
    await updateDoc(doc(fs, 'devices', uid), {
      approved: true,
      expiresAt: Timestamp.fromDate(expires),
      approvedAt: r.approvedAt || serverTimestamp()
    });
    // Lets the rider's phone write its location until then (database.rules.json)
    await set(ref(db, `members/${state.company}/${uid}`), expires.getTime());
  } catch (err) {
    alert('Could not approve: ' + err.message);
  }
}

async function block(uid, r) {
  if (!confirm(`Block ${r.name || 'this rider'}? The app locks on their phone.`)) return;
  try {
    await updateDoc(doc(fs, 'devices', uid), { approved: false });
    await remove(ref(db, `members/${state.company}/${uid}`));
    await remove(ref(db, `live/${state.company}/${uid}`));
  } catch (err) {
    alert('Could not block: ' + err.message);
  }
}

async function removeRider(uid, r) {
  if (!confirm(`Delete ${r.name || 'this rider'}? They have to send a new request to use the app again.`)) return;
  try {
    await remove(ref(db, `members/${state.company}/${uid}`));
    await remove(ref(db, `live/${state.company}/${uid}`));
    await deleteDoc(doc(fs, 'devices', uid));
  } catch (err) {
    alert('Could not delete: ' + err.message);
  }
}

function editSip(uid, r) {
  const dialog = $('sip-dialog');
  $('sip-title').textContent = `SIP account · ${r.name || 'rider'}`;
  $('sip-user').value = r.sipUsername || '';
  $('sip-pass').value = r.sipPassword || '';
  $('sip-domain').value = r.sipDomain || '';
  $('sip-port').value = r.sipPort || 5060;
  dialog.onclose = async () => {
    if (dialog.returnValue !== 'save') return;
    const user = $('sip-user').value.trim();
    const pass = $('sip-pass').value;
    const domain = $('sip-domain').value.trim();
    try {
      await updateDoc(doc(fs, 'devices', uid), {
        sipUsername: user,
        sipPassword: pass,
        sipDomain: domain,
        sipPort: Number($('sip-port').value) || 5060,
        sipDisplayName: r.name || ''
      });
    } catch (err) {
      alert('Could not save: ' + err.message);
    }
  };
  dialog.showModal();
}

// ---------- companies (main admin) ----------

function renderCompanies() {
  const body = $('companies-body');
  const rows = [...state.companies.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  if (!rows.length) {
    body.replaceChildren(el('tr', {}, el('td', { colspan: 5, class: 'muted' }, 'No companies yet.')));
    return;
  }
  body.replaceChildren(...rows.map(([code, c]) => {
    const exp = toMillis(c.expiresAt);
    const ended = exp && exp < Date.now();
    const days = el('select', {}, ...[30, 90, 365].map((d) => el('option', { value: d }, `${d} days`)), el('option', { value: 0 }, 'No limit'));
    return el('tr', {},
      el('td', {}, el('b', {}, code)),
      el('td', {}, c.name || ''),
      el('td', {}, (c.adminEmails || []).join(', ') || el('span', { class: 'muted' }, 'none'),
        ' ', el('button', { class: 'link', onclick: () => addCompanyAdmin(code) }, '+ Add')),
      el('td', {}, el('span', { class: `status ${ended ? 'expired' : 'active'}` }, exp ? (ended ? `Ended ${dateText(exp)}` : `Until ${dateText(exp)}`) : 'No limit')),
      el('td', {}, el('div', { class: 'row' }, days, el('button', { onclick: () => extendCompany(code, c, +days.value) }, 'Extend')))
    );
  }));
}

function companyExpiry(days, from = Date.now()) {
  return Timestamp.fromDate(days > 0 ? new Date(from + days * DAY) : FAR_FUTURE);
}

/** Creates a Firebase email/password user without signing the main admin out */
async function createAdminAccount(email, password) {
  const second = initializeApp(firebaseConfig, 'create-' + Date.now());
  try {
    const secondAuth = getAuth(second);
    if (EMULATOR) connectAuthEmulator(secondAuth, 'http://127.0.0.1:9099', { disableWarnings: true });
    const cred = await createUserWithEmailAndPassword(secondAuth, email, password);
    return cred.user.uid;
  } finally {
    await deleteApp(second);
  }
}

$('company-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const msg = $('company-form-msg');
  const code = $('c-code').value.replace(/[^a-z0-9]/gi, '').toUpperCase().slice(0, 20);
  const name = $('c-name').value.trim();
  const email = $('c-email').value.trim().toLowerCase();
  const password = $('c-password').value;
  if (code.length < 3) { msg.textContent = 'The code needs at least 3 letters or digits.'; return; }
  msg.textContent = 'Creating…';
  try {
    if ((await getDoc(doc(fs, 'companies', code))).exists()) { msg.textContent = `Code ${code} is already used.`; return; }
    const uid = await createAdminAccount(email, password);
    await setDoc(doc(fs, 'companies', code), {
      name,
      adminUids: [uid],
      adminEmails: [email],
      expiresAt: companyExpiry(+$('c-days').value),
      createdAt: serverTimestamp()
    });
    await set(ref(db, `companyAdmins/${code}/${uid}`), true);
    msg.textContent = `Company ${code} created. The admin signs in here with ${email}; riders type ${code} in the app.`;
    e.target.reset();
  } catch (err) {
    msg.textContent = err.code === 'auth/email-already-in-use'
      ? 'That email already has an account. Use another email for this company admin.'
      : 'Could not create: ' + err.message;
  }
});

async function extendCompany(code, c, days) {
  const current = toMillis(c.expiresAt);
  const from = current && current > Date.now() ? current : Date.now();
  try {
    await updateDoc(doc(fs, 'companies', code), { expiresAt: companyExpiry(days, from) });
  } catch (err) {
    alert('Could not extend: ' + err.message);
  }
}

async function addCompanyAdmin(code) {
  const email = prompt(`New admin email for ${code}:`)?.trim().toLowerCase();
  if (!email) return;
  const password = prompt(`Password for ${email} (at least 6 characters):`);
  if (!password) return;
  try {
    const uid = await createAdminAccount(email, password);
    await updateDoc(doc(fs, 'companies', code), { adminUids: arrayUnion(uid), adminEmails: arrayUnion(email) });
    await set(ref(db, `companyAdmins/${code}/${uid}`), true);
    alert(`${email} can now sign in and manage ${code}.`);
  } catch (err) {
    alert(err.code === 'auth/email-already-in-use' ? 'That email already has an account.' : 'Could not add: ' + err.message);
  }
}
