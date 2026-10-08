'use strict';

/**
 * Creates a LabUnzip shortcut on the Desktop (and in the app folder).
 *
 * A shortcut rather than a .cmd, for two reasons: no console window flashes up,
 * and Windows passes anything dropped onto a shortcut through as an argument,
 * so a zip can be dragged onto the icon and it opens straight into the plan.
 */

const path = require('path');
const fs = require('fs');
const os = require('os');
const { execFileSync } = require('child_process');

function resolveTarget() {
  const root = path.resolve(__dirname, '..');

  const packaged = [
    path.join(root, 'dist', 'win-unpacked', 'LabUnzip.exe'),
    path.join(process.env.LOCALAPPDATA || '', 'Programs', 'LabUnzip', 'LabUnzip.exe'),
  ].find((p) => p && fs.existsSync(p));

  if (packaged) return { exe: packaged, args: '', mode: 'packaged' };

  const electron = path.join(root, 'node_modules', 'electron', 'dist', 'electron.exe');
  if (!fs.existsSync(electron)) {
    throw new Error('Neither a built LabUnzip.exe nor electron was found. Run "npm install" first.');
  }
  return { exe: electron, args: `"${root}"`, mode: 'development' };
}

/**
 * Asks Windows where the Desktop is rather than assuming ~/Desktop.
 * OneDrive commonly redirects it to ~/OneDrive/Desktop, and a shortcut written
 * to the un-redirected path lands in a folder the user never looks at.
 */
function desktopPath() {
  try {
    const out = execFileSync(
      'powershell.exe',
      ['-NoProfile', '-NonInteractive', '-Command', '[Environment]::GetFolderPath("Desktop")'],
      { encoding: 'utf8' },
    ).trim();
    if (out && fs.existsSync(out)) return out;
  } catch { /* fall through to the default below */ }
  return path.join(os.homedir(), 'Desktop');
}

function createShortcut(linkPath, exe, args, workingDir) {
  const ps = `
    $shell = New-Object -ComObject WScript.Shell
    $link = $shell.CreateShortcut('${linkPath.replace(/'/g, "''")}')
    $link.TargetPath = '${exe.replace(/'/g, "''")}'
    $link.Arguments = '${args.replace(/'/g, "''")}'
    $link.WorkingDirectory = '${workingDir.replace(/'/g, "''")}'
    $link.IconLocation = '${exe.replace(/'/g, "''")}'
    $link.Description = 'Unpack film lab zips, nested zips and all'
    $link.Save()
  `;
  execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command', ps], {
    stdio: ['ignore', 'pipe', 'pipe'],
  });
}

function main() {
  if (process.platform !== 'win32') {
    console.error('This only applies to Windows.');
    process.exit(1);
  }

  const root = path.resolve(__dirname, '..');
  const { exe, args, mode } = resolveTarget();

  const desktop = desktopPath();
  const targets = [
    path.join(desktop, 'LabUnzip.lnk'),
    path.join(root, 'LabUnzip.lnk'),
  ];

  // Clean up a shortcut left in the un-redirected Desktop by an older run.
  const strayDesktop = path.join(os.homedir(), 'Desktop', 'LabUnzip.lnk');
  if (strayDesktop !== targets[0] && fs.existsSync(strayDesktop)) {
    try {
      fs.unlinkSync(strayDesktop);
      console.log(`Removed a stray shortcut from ${strayDesktop}`);
    } catch { /* leave it, it is harmless */ }
  }

  const made = [];
  for (const link of targets) {
    try {
      fs.mkdirSync(path.dirname(link), { recursive: true });
      createShortcut(link, exe, args, root);
      made.push(link);
    } catch (err) {
      console.error(`Could not create ${link}: ${err.message}`);
    }
  }

  console.log(`Created ${made.length} shortcut(s) for the ${mode} build:`);
  for (const link of made) console.log(`  ${link}`);
  console.log('\nDouble-click it to open LabUnzip, or drag a zip onto the icon to go');
  console.log('straight to the plan for that archive.');
}

try {
  main();
} catch (err) {
  console.error('Could not create the shortcut:', err.message);
  process.exit(1);
}
