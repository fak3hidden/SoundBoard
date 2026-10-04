/* Quest Soundboard — browser control panel.
 * Talks to the HTTP server running inside the headset app. */

const $ = (sel) => document.querySelector(sel);
const api = (path, opts) => fetch(path, { method: 'POST', ...opts }).then(r => r.json());

let state = { sounds: [], categories: [], playing: [], route: 'MIC_AND_MONITOR' };
let activeCategory = 'All';
let search = '';
let editingId = null;
let draggingSliders = false;

/* ------------------------------------------------------------------ render */

function render() {
  renderStatus();
  renderTabs();
  renderGrid();
  renderNowPlaying();
}

function renderStatus() {
  const root = state.root || {};
  $('#deviceLine').textContent =
    `${root.device || 'headset'} · Android ${root.android || '?'} · ${state.sounds.length} clips`;

  const rootChip = $('#rootChip');
  const tier = root.tier || 'NONE';
  rootChip.className = 'chip ' + (tier === 'PRIVILEGED' ? 'good' : tier === 'ROOT' ? 'warn' : 'bad');
  rootChip.lastElementChild.textContent =
    tier === 'PRIVILEGED' ? `root: ${root.provider} + module`
    : tier === 'ROOT' ? `root: ${root.provider}`
    : 'root: none';

  const mic = state.micInjector || {};
  const micChip = $('#micChip');
  micChip.className = 'chip ' + (mic.state === 'ACTIVE' ? 'good' : mic.state === 'IDLE' ? 'warn' : 'bad');
  micChip.lastElementChild.textContent = 'mic: ' + (mic.state || '?').toLowerCase();

  $('#meterFill').style.width = Math.round((state.peak || 0) * 100) + '%';

  document.querySelectorAll('.route').forEach(btn => {
    btn.classList.toggle('active', btn.dataset.route === state.route);
  });

  if (!draggingSliders) {
    $('#master').value = Math.round((state.masterVolume ?? 0.9) * 100);
    $('#masterOut').textContent = $('#master').value + '%';
    $('#monitor').value = Math.round((state.monitorVolume ?? 0.6) * 100);
    $('#monitorOut').textContent = $('#monitor').value + '%';
  }

  const banner = $('#banner');
  let msg = '';
  let bad = false;
  if (state.libraryError) {
    msg = state.libraryError; bad = true;
  } else if (tier === 'NONE') {
    msg = 'No root detected. The board still works in <strong>Acoustic</strong> mode ' +
          '(played out loud so the headset mic picks it up). For clean mic injection, ' +
          'root the headset and install the Magisk module from the headset app.';
  } else if (tier === 'ROOT' && !root.moduleInstalled) {
    msg = 'Root found, but the virtual-mic Magisk module is not installed. ' +
          'Open the headset app, tap <code>Install module</code>, then reboot to unlock true mic injection.';
  } else if (tier === 'ROOT' && root.moduleInstalled) {
    msg = 'Module installed — <strong>reboot the headset</strong> to finish granting MODIFY_AUDIO_ROUTING.';
  } else if (mic.state === 'UNAVAILABLE' && mic.error) {
    msg = 'Mic injection error: ' + mic.error; bad = true;
  }
  banner.classList.toggle('hidden', !msg);
  banner.classList.toggle('bad', bad);
  banner.innerHTML = msg;
}

function renderTabs() {
  const tabs = ['All'].concat(state.categories || []);
  const el = $('#tabs');
  if (el.dataset.sig === tabs.join('|')) {
    el.querySelectorAll('.tab').forEach(t =>
      t.classList.toggle('active', t.textContent === activeCategory));
    return;
  }
  el.dataset.sig = tabs.join('|');
  el.innerHTML = '';
  tabs.forEach(name => {
    const b = document.createElement('button');
    b.className = 'tab' + (name === activeCategory ? ' active' : '');
    b.textContent = name;
    b.onclick = () => { activeCategory = name; $('#grid').dataset.sig = ''; render(); };
    el.appendChild(b);
  });
}

function visibleSounds() {
  const q = search.trim().toLowerCase();
  return (state.sounds || []).filter(s =>
    (activeCategory === 'All' || s.category === activeCategory) &&
    (!q || s.name.toLowerCase().includes(q))
  ).sort((a, b) => (b.pad > 0) - (a.pad > 0) || a.pad - b.pad || a.name.localeCompare(b.name));
}

function renderGrid() {
  const grid = $('#grid');
  const list = visibleSounds();
  $('#empty').classList.toggle('hidden', list.length > 0);

  const playingBySound = {};
  (state.playing || []).forEach(p => { playingBySound[p.soundId] = p; });

  const sig = list.map(s => s.id + ':' + s.pad + ':' + s.hotkey + ':' + s.loop).join('|');
  if (grid.dataset.sig !== sig) {
    grid.dataset.sig = sig;
    grid.innerHTML = '';
    list.forEach(s => grid.appendChild(padElement(s)));
  }

  list.forEach(s => {
    const el = grid.querySelector('[data-id="' + CSS.escape(s.id) + '"]');
    if (!el) return;
    const p = playingBySound[s.id];
    el.classList.toggle('playing', !!p);
    el.style.setProperty('--progress',
      p && p.durationMs ? Math.min(100, (p.positionMs / p.durationMs) * 100) + '%' : '0%');
  });
}

function padElement(s) {
  const el = document.createElement('button');
  el.className = 'pad';
  el.dataset.id = s.id;

  const name = document.createElement('div');
  name.className = 'name';
  name.textContent = s.name;

  const meta = document.createElement('div');
  meta.className = 'meta';
  if (s.pad > 0) meta.appendChild(badge('PAD ' + s.pad));
  if (s.hotkey) meta.appendChild(badge(String(s.hotkey).replace(/^(BTN_|KEY_)/, ''), 'key'));
  if (s.loop) meta.appendChild(badge('LOOP', 'loop'));
  const size = document.createElement('span');
  size.textContent = (s.sizeBytes / 1024 / 1024).toFixed(1) + ' MB';
  meta.appendChild(size);

  const edit = document.createElement('span');
  edit.className = 'edit';
  edit.textContent = '⚙';
  edit.onclick = (e) => { e.stopPropagation(); openEditor(s); };

  el.append(edit, name, meta);
  el.onclick = () => trigger(s);
  return el;
}

function badge(text, cls) {
  const b = document.createElement('span');
  b.className = 'badge ' + (cls || '');
  b.textContent = text;
  return b;
}

function renderNowPlaying() {
  const el = $('#nowPlaying');
  const list = state.playing || [];
  el.innerHTML = '';
  list.forEach(p => {
    const item = document.createElement('div');
    item.className = 'np-item';
    const secs = (ms) => (ms / 1000).toFixed(1);
    item.innerHTML = '<span>▶ ' + escapeHtml(p.name) + '</span>' +
      '<span style="color:var(--muted)">' + secs(p.positionMs) + 's / ' + secs(p.durationMs) + 's</span>';
    const stop = document.createElement('button');
    stop.textContent = '✕';
    stop.onclick = () => api('/api/stop/' + encodeURIComponent(p.soundId));
    item.appendChild(stop);
    el.appendChild(item);
  });
}

const escapeHtml = (s) => String(s).replace(/[&<>"]/g, c =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

/* ----------------------------------------------------------------- actions */

function trigger(s) {
  if (navigator.vibrate) navigator.vibrate(12);
  const playing = (state.playing || []).some(p => p.soundId === s.id);
  api((playing && s.loop ? '/api/stop/' : '/api/play/') + encodeURIComponent(s.id));
}

function openEditor(s) {
  editingId = s.id;
  $('#editName').textContent = s.name;
  $('#editVolume').value = Math.round(s.volume * 100);
  $('#editVolumeOut').textContent = $('#editVolume').value + '%';
  $('#editLoop').checked = !!s.loop;
  $('#editPad').value = s.pad || 0;
  $('#editHotkey').value = s.hotkey || '';
  $('#hotkeyHint').textContent = (state.root && state.root.rooted)
    ? 'Global hotkeys fire even while a game has focus.'
    : 'Root required — without it, hotkeys only work in this browser.';
  $('#editor').showModal();
}

$('#editVolume').oninput = (e) => { $('#editVolumeOut').textContent = e.target.value + '%'; };

$('#captureKey').onclick = async () => {
  const btn = $('#captureKey');
  btn.textContent = 'Press any button…';
  await api('/api/hotkey/capture');
  const deadline = Date.now() + 10000;
  const poll = setInterval(async () => {
    const r = await api('/api/hotkey/captured');
    if (r.key) {
      $('#editHotkey').value = r.key;
      btn.textContent = 'Press a button…';
      clearInterval(poll);
    } else if (Date.now() > deadline) {
      btn.textContent = 'Press a button…';
      clearInterval(poll);
    }
  }, 300);
};

$('#editor').addEventListener('close', async () => {
  const action = $('#editor').returnValue;
  if (!editingId) return;
  if (action === 'save') {
    const q = new URLSearchParams({
      volume: ($('#editVolume').value / 100).toFixed(2),
      loop: $('#editLoop').checked,
      pad: $('#editPad').value || 0,
      hotkey: $('#editHotkey').value || 'none'
    });
    await api('/api/sound/' + encodeURIComponent(editingId) + '?' + q);
    refresh();
  } else if (action === 'delete') {
    if (confirm('Delete this file from the headset?')) {
      await fetch('/api/sound/' + encodeURIComponent(editingId), { method: 'DELETE' });
      refresh();
    }
  }
  editingId = null;
});

document.querySelectorAll('.route').forEach(btn => {
  btn.onclick = () => api('/api/route?value=' + btn.dataset.route).then(refresh);
});

$('#panic').onclick = () => {
  if (navigator.vibrate) navigator.vibrate([20, 30, 20]);
  api('/api/stopall');
};
$('#rescan').onclick = () => api('/api/rescan').then(refresh);
$('#search').oninput = (e) => { search = e.target.value; $('#grid').dataset.sig = ''; renderGrid(); };

['master', 'monitor'].forEach(id => {
  const el = $('#' + id);
  el.addEventListener('pointerdown', () => { draggingSliders = true; });
  el.addEventListener('pointerup', () => { setTimeout(() => { draggingSliders = false; }, 400); });
  el.oninput = () => {
    $('#' + id + 'Out').textContent = el.value + '%';
    api('/api/volume?' + id + '=' + (el.value / 100).toFixed(2));
  };
});

/* ------------------------------------------------------------------ upload */

async function upload(files) {
  for (const file of files) {
    const category = activeCategory === 'All' ? 'General' : activeCategory;
    await fetch('/api/upload?name=' + encodeURIComponent(file.name) +
      '&category=' + encodeURIComponent(category), {
      method: 'POST',
      body: await file.arrayBuffer()
    });
  }
  refresh();
}

$('#fileInput').onchange = (e) => upload(e.target.files);

let dragDepth = 0;
window.addEventListener('dragenter', (e) => {
  e.preventDefault(); dragDepth++; $('#dropzone').classList.add('visible');
});
window.addEventListener('dragleave', () => {
  if (--dragDepth <= 0) $('#dropzone').classList.remove('visible');
});
window.addEventListener('dragover', (e) => e.preventDefault());
window.addEventListener('drop', (e) => {
  e.preventDefault(); dragDepth = 0;
  $('#dropzone').classList.remove('visible');
  if (e.dataTransfer.files.length) upload(e.dataTransfer.files);
});

/* ------------------------------------------- number keys fire the pad slots */

window.addEventListener('keydown', (e) => {
  if (e.target.matches('input, textarea')) return;
  if (e.key === 'Escape') { api('/api/stopall'); return; }
  const n = parseInt(e.key, 10);
  if (!isNaN(n)) {
    const s = (state.sounds || []).find(x => x.pad === (n === 0 ? 10 : n));
    if (s) trigger(s);
  }
});

/* ------------------------------------------------------------------ stream */

function refresh() {
  return fetch('/api/state')
    .then(r => r.json())
    .then(s => { state = s; render(); })
    .catch(() => {});
}

function connect() {
  try {
    const es = new EventSource('/api/events');
    es.onmessage = (e) => { state = JSON.parse(e.data); render(); };
    es.onerror = () => { es.close(); setTimeout(connect, 1500); };
  } catch (err) {
    setInterval(refresh, 500);
  }
}

refresh().then(connect);
