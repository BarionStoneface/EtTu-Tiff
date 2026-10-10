'use strict';

/**
 * The plan you see is built by the same buildOutputPlan() that does the real
 * run - the renderer never works out filenames on its own, so the preview
 * cannot drift away from what actually gets written.
 */

const api = window.labunzip;

const state = {
  zipPath: null,
  scan: null,
  draft: null,
  destination: '',
  plan: null,
};

const $ = (id) => document.getElementById(id);
const panes = ['emptyState', 'scanningState', 'planState', 'runState', 'doneState'];

function show(paneId) {
  for (const id of panes) $(id).hidden = id !== paneId;
}

function formatBytes(bytes) {
  if (!bytes) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  return `${(bytes / 1024 ** i).toFixed(i === 0 ? 0 : 1)} ${units[i]}`;
}

function formatDate(iso) {
  if (!iso) return 'no date';
  const d = new Date(iso);
  return d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}

function text(tag, className, content) {
  const el = document.createElement(tag);
  if (className) el.className = className;
  if (content !== undefined) el.textContent = content;
  return el;
}

// ---------------------------------------------------------------- opening

async function openArchive(zipPath) {
  if (!zipPath) return;
  state.zipPath = zipPath;
  $('archiveName').textContent = zipPath;
  show('scanningState');
  $('scanDetail').textContent = 'Nothing is written until you approve the plan.';

  const result = await api.scan(zipPath);
  if (!result.ok) {
    show('emptyState');
    $('archiveName').textContent = `Could not read that archive: ${result.error}`;
    return;
  }

  state.scan = result.scan;
  state.draft = result.draft;
  // Default to a "Processed" folder beside the zip, so Unpack is never blocked
  // on picking somewhere first.
  state.destination = zipPath.replace(/\\[^\\/]*$/, '').replace(/\/[^/]*$/, '');
  $('destPath').value = state.destination;

  renderFolders();
  await refreshPlan();
  show('planState');
}

// ------------------------------------------------------------- the plan UI

function renderFolders() {
  const list = $('folderList');
  list.replaceChildren();

  for (const folder of state.draft.folders) {
    // Folders that only hold other folders have nothing to configure beyond
    // their name, so they get a slimmer row.
    const node = text('div', 'folder');
    node.dataset.path = folder.path;

    const head = text('div', 'folder-head');
    head.appendChild(text('span', 'folder-path', folder.path));
    if (folder.fileCount > 0) {
      head.appendChild(text('span', 'badge', `${folder.fileCount} file${folder.fileCount === 1 ? '' : 's'}`));
      head.appendChild(text('span', 'badge', formatBytes(folder.totalBytes)));
    }
    if (folder.undatedCount > 0) {
      head.appendChild(text('span', 'badge warn', `${folder.undatedCount} with no date`));
    } else if (folder.fileCount > 0) {
      head.appendChild(text('span', 'badge good', 'all dated'));
    }
    node.appendChild(head);

    const body = text('div', 'folder-body');
    const left = text('div');
    const right = text('div');

    left.appendChild(field('Folder name', folder.outputName, (value) => {
      folder.outputName = value;
      refreshPlan();
    }, 'Leave empty to skip this folder level'));

    if (folder.fileCount > 0) {
      const row = text('div', 'row3');
      row.appendChild(field('Add before the number', folder.pattern.prefix, (v) => {
        folder.pattern.prefix = v;
        refreshPlan();
      }, 'e.g. Portra400'));
      row.appendChild(field('Join with', folder.pattern.separator, (v) => {
        folder.pattern.separator = v;
        refreshPlan();
      }));
      row.appendChild(field('Add after the number', folder.pattern.suffix, (v) => {
        folder.pattern.suffix = v;
        refreshPlan();
      }, 'e.g. RoyalWe'));
      left.appendChild(row);

      left.appendChild(metadataEditor(folder));

      const matching = state.draft.folders.filter(
        (f) => f !== folder && f.fileCount > 0 && f.sourceName === folder.sourceName,
      );
      if (matching.length) {
        const copy = text('button', 'copybtn', `Copy these settings to the ${matching.length} other folder${matching.length === 1 ? '' : 's'} named "${folder.sourceName}"`);
        copy.addEventListener('click', () => {
          for (const other of matching) {
            other.outputName = folder.outputName;
            other.pattern = { ...folder.pattern };
            other.metadata = { ...folder.metadata };
          }
          renderFolders();
          refreshPlan();
        });
        left.appendChild(copy);
      }

      const preview = text('div', 'preview');
      preview.dataset.previewFor = folder.path;
      right.appendChild(preview);

      const dates = text('div', 'datenote');
      dates.textContent = folder.dateSources.length
        ? `Dates from ${folder.dateSources.map((d) => `${d.label} (${d.count})`).join(', ')}`
        : '';
      right.appendChild(dates);
    }

    body.appendChild(left);
    body.appendChild(right);
    node.appendChild(body);
    list.appendChild(node);
  }
}

function field(labelText, value, onInput, placeholder) {
  const wrap = text('div', 'field');
  const label = text('label', null, labelText);
  const input = document.createElement('input');
  input.type = 'text';
  input.value = value === undefined || value === null ? '' : value;
  if (placeholder) input.placeholder = placeholder;
  input.addEventListener('input', () => onInput(input.value));
  wrap.appendChild(label);
  wrap.appendChild(input);
  return wrap;
}

function metadataEditor(folder) {
  const wrap = text('div', 'field');
  wrap.appendChild(text('label', null, 'Metadata written to each photo'));

  const rows = text('div', 'meta-rows');
  const entries = Object.entries(folder.metadata);
  if (entries.length === 0) entries.push(['Model', '']);

  const rebuild = () => {
    folder.metadata = {};
    for (const row of rows.children) {
      const key = row.children[0].value.trim();
      const value = row.children[1].value.trim();
      if (key && value) folder.metadata[key] = value;
    }
    refreshPlan();
  };

  const addRow = (key, value) => {
    const row = text('div', 'meta-row');
    const keyInput = document.createElement('input');
    keyInput.type = 'text';
    keyInput.value = key;
    keyInput.placeholder = 'Tag, e.g. Model';
    const valInput = document.createElement('input');
    valInput.type = 'text';
    valInput.value = value;
    valInput.placeholder = 'Value, e.g. Canon AV-1';
    const remove = text('button', null, '\u00d7');
    remove.title = 'Remove this field';
    remove.addEventListener('click', () => { row.remove(); rebuild(); });
    keyInput.addEventListener('input', rebuild);
    valInput.addEventListener('input', rebuild);
    row.appendChild(keyInput);
    row.appendChild(valInput);
    row.appendChild(remove);
    rows.appendChild(row);
  };

  for (const [key, value] of entries) addRow(key, value);
  wrap.appendChild(rows);

  const add = text('button', 'addmeta', '+ Add another field');
  add.addEventListener('click', () => { addRow('', ''); });
  wrap.appendChild(add);
  return wrap;
}

// ------------------------------------------------------- plan refreshing

let refreshTimer = null;
function refreshPlan() {
  return new Promise((resolve) => {
    clearTimeout(refreshTimer);
    refreshTimer = setTimeout(async () => {
      const result = await api.buildPlan({
        scan: state.scan,
        draft: state.draft,
        destination: state.destination || 'C:\\',
      });
      if (result.ok) {
        state.plan = result.plan;
        paintPlan();
      }
      resolve();
    }, 120);
  });
}

function paintPlan() {
  const plan = state.plan;
  if (!plan) return;

  $('summary').replaceChildren(
    summaryItem('Files', String(plan.files.length)),
    summaryItem('Folders', String(plan.folders.length)),
    summaryItem('Total size', formatBytes(plan.totalBytes)),
    summaryItem('Without a date', String(plan.undatedCount)),
  );

  const conflicts = $('conflicts');
  if (plan.conflicts.length) {
    conflicts.hidden = false;
    conflicts.textContent =
      `${plan.conflicts.length} file${plan.conflicts.length === 1 ? '' : 's'} would be written to a path already taken by another file. ` +
      `First clash: ${plan.conflicts[0].targetPath}. Change a folder name or add a prefix to separate them.`;
  } else {
    conflicts.hidden = true;
  }

  // Group the planned files back under their folder for the preview column.
  const byFolder = new Map();
  for (const file of plan.files) {
    if (!byFolder.has(file.sourcePath)) byFolder.set(file.sourcePath, file);
  }

  for (const previewEl of document.querySelectorAll('[data-preview-for]')) {
    const folderPath = previewEl.dataset.previewFor;
    const folder = state.scan ? findNode(state.scan.root, folderPath) : null;
    if (!folder) continue;

    const entryPaths = new Set(folder.files.map((f) => f.entryPath));
    const rows = plan.files.filter((f) => entryPaths.has(f.sourcePath));
    const clashing = new Set(plan.conflicts.map((c) => c.targetPath.toLowerCase()));

    previewEl.replaceChildren();
    for (const row of rows.slice(0, 6)) {
      const line = text('div');
      line.appendChild(text('span', 'was', row.originalName));
      line.appendChild(text('span', 'arrow', '\u2192'));
      const isClash = clashing.has(row.targetPath.toLowerCase());
      line.appendChild(text('span', isClash ? 'clash' : 'now', row.outputName));
      previewEl.appendChild(line);
    }
    if (rows.length > 6) {
      previewEl.appendChild(text('div', 'more', `and ${rows.length - 6} more, the same way`));
    }
    if (rows.length) {
      previewEl.appendChild(text('div', 'more', `First file dated ${formatDate(rows[0].date)}`));
    }
  }

  const ready = Boolean(state.destination) && plan.conflicts.length === 0 && plan.files.length > 0;
  $('runBtn').disabled = !ready;
  $('planStatus').textContent = !state.destination
    ? 'Choose a destination to continue.'
    : plan.conflicts.length
      ? 'Resolve the clashes above to continue.'
      : `Ready to write ${plan.files.length} files into ${state.destination}`;
}

function summaryItem(label, value) {
  const el = text('span');
  el.appendChild(document.createTextNode(`${label}: `));
  el.appendChild(text('strong', null, value));
  return el;
}

function findNode(node, wantedPath) {
  if (node.path === wantedPath) return node;
  for (const child of node.folders) {
    const found = findNode(child, wantedPath);
    if (found) return found;
  }
  return null;
}

// ------------------------------------------------------------- the run

async function run() {
  show('runState');
  $('progressBar').style.width = '0%';
  $('runPhase').textContent = 'Unpacking';
  $('runDetail').textContent = '';

  const response = await api.run({
    zipPath: state.zipPath,
    scan: state.scan,
    draft: state.draft,
    destination: state.destination,
  });

  if (!response.ok) {
    show('planState');
    $('planStatus').textContent = response.error;
    return;
  }

  const { result } = response;
  $('doneTitle').textContent = result.cancelled ? 'Stopped' : 'Done';

  const summary = $('doneSummary');
  summary.replaceChildren(
    statLine('Files written', String(result.written)),
    statLine('Dates set from EXIF or the archive', String(result.written - result.skippedUndated)),
    statLine('Left with no date', String(result.skippedUndated)),
    statLine('Created dates set', String(result.createdDatesSet)),
  );

  const errorBox = $('doneErrors');
  if (result.errors.length) {
    errorBox.hidden = false;
    errorBox.replaceChildren(
      text('div', null, `${result.errors.length} problem${result.errors.length === 1 ? '' : 's'}:`),
      ...result.errors.slice(0, 20).map((e) => text('div', null, `${e.stage}: ${e.file} - ${e.error}`)),
    );
  } else {
    errorBox.hidden = true;
  }

  show('doneState');
}

function statLine(label, value) {
  const row = text('div', 'statline');
  row.appendChild(text('span', null, label));
  row.appendChild(text('span', null, value));
  return row;
}

// ------------------------------------------------------------- wiring

$('openBtn').addEventListener('click', async () => openArchive(await api.pickArchive()));
$('openBtn2').addEventListener('click', async () => openArchive(await api.pickArchive()));

$('destBtn').addEventListener('click', async () => {
  const picked = await api.pickDestination(state.destination);
  if (!picked) return;
  state.destination = picked;
  $('destPath').value = picked;
  refreshPlan();
});

$('runBtn').addEventListener('click', run);
$('cancelBtn').addEventListener('click', () => api.cancel());
$('revealBtn').addEventListener('click', () => api.reveal(state.destination));
$('againBtn').addEventListener('click', async () => {
  show('emptyState');
  $('archiveName').textContent = 'No archive open';
  openArchive(await api.pickArchive());
});

api.onScanProgress((p) => {
  $('scanDetail').textContent = p.phase === 'opening'
    ? `Opening ${p.detail}`
    : `Read ${p.files} files so far`;
});

api.onRunProgress((p) => {
  if (p.phase === 'extracting') {
    $('runPhase').textContent = 'Unpacking';
    $('progressBar').style.width = `${Math.round((p.done / Math.max(p.total, 1)) * 100)}%`;
    $('runDetail').textContent = `${p.done} of ${p.total}  -  ${p.detail}`;
  } else if (p.phase === 'metadata') {
    $('runPhase').textContent = 'Writing metadata';
    $('progressBar').style.width = `${Math.round((p.done / Math.max(p.total, 1)) * 100)}%`;
    $('runDetail').textContent = `${p.done} of ${p.total}  -  ${p.detail}`;
  } else if (p.phase === 'timestamps') {
    $('runPhase').textContent = 'Setting dates';
    $('progressBar').style.width = '100%';
    $('runDetail').textContent = `${p.total} files`;
  }
});

api.onOpenArchive((zipPath) => openArchive(zipPath));

// Dropping a zip anywhere on the window opens it, so the app is usable without
// the right-click menu.
window.addEventListener('dragover', (event) => {
  event.preventDefault();
  document.body.classList.add('dragging');
});
window.addEventListener('dragleave', (event) => {
  if (event.relatedTarget === null) document.body.classList.remove('dragging');
});
window.addEventListener('drop', (event) => {
  event.preventDefault();
  document.body.classList.remove('dragging');
  const file = event.dataTransfer.files[0];
  if (!file) return;
  const dropped = api.pathForFile(file);
  if (!dropped) return;
  if (!/\.zip$/i.test(dropped)) {
    $('archiveName').textContent = 'That is not a zip file.';
    return;
  }
  openArchive(dropped);
});

api.startupArchive().then((zipPath) => {
  if (zipPath) openArchive(zipPath);
  else show('emptyState');
});
