'use strict';

const { app, BrowserWindow, dialog, ipcMain, shell } = require('electron');
const path = require('path');
const fs = require('fs');

const { scanArchive } = require('./src/zipscan');
const { planFrom, buildOutputPlan } = require('./src/plan');
const { runPlan } = require('./src/extract');

let mainWindow = null;
let pendingZipPath = null;
let cancelRequested = false;
let exiftoolInstance = null;

/**
 * The zip arrives as a command-line argument when launched from the right-click
 * menu. In development the first argument is the app directory, so anything
 * that is not an existing .zip is ignored.
 */
function zipFromArgv(argv) {
  for (const arg of argv.slice(1)) {
    if (typeof arg !== 'string' || arg.startsWith('--')) continue;
    if (path.extname(arg).toLowerCase() !== '.zip') continue;
    try {
      if (fs.statSync(arg).isFile()) return path.resolve(arg);
    } catch { /* not a real path */ }
  }
  return null;
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1180,
    height: 820,
    minWidth: 900,
    minHeight: 600,
    backgroundColor: '#1b1f24',
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  mainWindow.setMenuBarVisibility(false);

  // Errors inside the page are otherwise invisible when the app is launched
  // from the right-click menu with no console attached.
  mainWindow.webContents.on('console-message', (_event, level, message, line, sourceId) => {
    if (level >= 2 || process.env.LABUNZIP_DEBUG) {
      console.error(`[renderer] ${message}  (${sourceId}:${line})`);
    }
  });
  mainWindow.webContents.on('render-process-gone', (_event, details) => {
    console.error('[renderer] process gone:', JSON.stringify(details));
  });
  mainWindow.webContents.on('preload-error', (_event, preloadPath, error) => {
    console.error('[preload] failed:', preloadPath, error.message);
  });

  mainWindow.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  mainWindow.once('ready-to-show', () => mainWindow.show());
  mainWindow.on('closed', () => { mainWindow = null; });
}

/**
 * "Fix dates with LabUnzip" on a folder: give every photo in it its scan date as its created and
 * modified date, say what happened, and quit. No window, and separate from an open LabUnzip.
 */
function folderToFix(argv) {
  const i = argv.indexOf('--fix-dates');
  if (i < 0) return null;
  const folder = argv[i + 1];
  try {
    if (folder && fs.statSync(folder).isDirectory()) return path.resolve(folder);
  } catch { /* not a real folder */ }
  return '';
}

const fixFolder = folderToFix(process.argv);

if (fixFolder !== null) {
  app.whenReady().then(async () => {
    const { fixDates, describe } = require('./src/fixdates');
    let message;
    let type = 'info';
    if (!fixFolder) {
      message = 'That isn\'t a folder LabUnzip can open.';
      type = 'error';
    } else {
      try {
        const result = await fixDates(fixFolder);
        message = describe(result, fixFolder);
        if (result.failed.length) type = 'warning';
      } catch (err) {
        message = `Couldn't fix the dates: ${err.message}`;
        type = 'error';
      }
    }
    await dialog.showMessageBox({ type, title: 'LabUnzip: fix dates', message: 'Fix dates', detail: message, buttons: ['OK'] });
    app.quit();
  });
} else if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', (_event, argv) => {
    const zip = zipFromArgv(argv);
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
      if (zip) mainWindow.webContents.send('open-archive', zip);
    }
  });

  app.whenReady().then(() => {
    pendingZipPath = zipFromArgv(process.argv);
    createWindow();
  });

  app.on('window-all-closed', async () => {
    if (exiftoolInstance) {
      try { await exiftoolInstance.end(); } catch { /* shutting down anyway */ }
      exiftoolInstance = null;
    }
    app.quit();
  });
}

/** ExifTool is only started if a plan actually asks for metadata. */
async function getExiftool() {
  if (exiftoolInstance) return exiftoolInstance;
  const { ExifTool } = require('exiftool-vendored');
  exiftoolInstance = new ExifTool({ taskTimeoutMillis: 60000 });
  return exiftoolInstance;
}

ipcMain.handle('startup-archive', () => {
  const zip = pendingZipPath;
  pendingZipPath = null;
  return zip;
});

ipcMain.handle('pick-archive', async () => {
  const result = await dialog.showOpenDialog(mainWindow, {
    title: 'Choose the zip from your lab',
    filters: [{ name: 'Zip archives', extensions: ['zip'] }],
    properties: ['openFile'],
  });
  return result.canceled ? null : result.filePaths[0];
});

ipcMain.handle('pick-destination', async (_event, current) => {
  const result = await dialog.showOpenDialog(mainWindow, {
    title: 'Where should the photos go?',
    defaultPath: current && fs.existsSync(current) ? current : undefined,
    properties: ['openDirectory', 'createDirectory'],
  });
  return result.canceled ? null : result.filePaths[0];
});

ipcMain.handle('scan', async (event, zipPath) => {
  try {
    const scan = await scanArchive(zipPath, (progress) => {
      event.sender.send('scan-progress', progress);
    });
    return { ok: true, scan, draft: planFrom(scan) };
  } catch (err) {
    return { ok: false, error: err.message };
  }
});

ipcMain.handle('build-plan', async (_event, { scan, draft, destination }) => {
  try {
    return { ok: true, plan: buildOutputPlan(scan, draft, destination) };
  } catch (err) {
    return { ok: false, error: err.message };
  }
});

ipcMain.handle('cancel', () => { cancelRequested = true; });

ipcMain.handle('run', async (event, { zipPath, scan, draft, destination }) => {
  cancelRequested = false;
  try {
    const plan = buildOutputPlan(scan, draft, destination);
    if (plan.conflicts.length) {
      return { ok: false, error: `${plan.conflicts.length} file(s) would overwrite each other. Adjust the names first.` };
    }

    const needsMetadata = plan.files.some((f) => f.metadata && Object.keys(f.metadata).length);
    const exiftool = needsMetadata ? await getExiftool() : null;

    const result = await runPlan({
      zipPath,
      outputPlan: plan,
      exiftool,
      isCancelled: () => cancelRequested,
      onProgress: (progress) => event.sender.send('run-progress', progress),
    });

    return { ok: true, result, destination };
  } catch (err) {
    return { ok: false, error: err.message };
  }
});

ipcMain.handle('reveal', async (_event, target) => {
  if (target && fs.existsSync(target)) shell.openPath(target);
});
