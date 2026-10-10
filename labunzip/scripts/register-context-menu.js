'use strict';

/**
 * Adds "Unpack with LabUnzip" to the right-click menu for .zip files.
 *
 * Everything goes under HKEY_CURRENT_USER, so this needs no administrator
 * rights and only affects the current user. `npm run unregister` removes it.
 */

const path = require('path');
const fs = require('fs');
const { execFileSync } = require('child_process');

const LABEL = 'Unpack with LabUnzip';

function reg(args) {
  return execFileSync('reg.exe', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
}

/**
 * Where to hang the menu entry.
 *
 * SystemFileAssociations\.zip is the documented place, but Explorer does not
 * reliably merge it for zips - .zip resolves to the CompressedFolder ProgID and
 * that is the branch Explorer actually reads. Both are written, since which one
 * takes effect depends on what the user has .zip associated with.
 */
function targetKeys() {
  const keys = [
    // .zip carries PerceivedType = compressed, and SystemFileAssociations keys
    // accept a perceived type as well as an extension. This is the properly
    // scoped hook: it covers archives and nothing else.
    { key: 'HKCU\\Software\\Classes\\SystemFileAssociations\\compressed\\shell\\LabUnzip' },
    // The documented per-extension place. Explorer does not reliably merge it
    // for zips, but it costs nothing to set.
    { key: 'HKCU\\Software\\Classes\\SystemFileAssociations\\.zip\\shell\\LabUnzip' },
  ];

  // Deliberately NOT registering under *\shell with AppliesTo. The key merges
  // fine - it shows up in HKCR\*\shell next to Notepad++ - but AppliesTo is
  // evaluated through the Windows Search property system, which silently hides
  // the entry when a file's properties are not indexed. Without AppliesTo the
  // verb would appear on every file of any type, which is worse.

  // Follow the user's own file association too, in case it is not the default.
  const progIds = new Set(['CompressedFolder']);
  for (const query of [
    ['query', 'HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Explorer\\FileExts\\.zip\\UserChoice', '/v', 'ProgId'],
    ['query', 'HKCR\\.zip', '/ve'],
  ]) {
    try {
      const match = reg(query).match(/REG_SZ\s+(\S+)/);
      if (match) progIds.add(match[1]);
    } catch { /* association not set, the default covers it */ }
  }

  for (const progId of progIds) {
    keys.push({ key: `HKCU\\Software\\Classes\\${progId}\\shell\\LabUnzip` });
  }
  return keys;
}

/**
 * Puts LabUnzip in the "Send to" menu.
 *
 * This is the one route that does not depend on file associations, ProgIDs or
 * Explorer's menu cache at all - it is just a shortcut in a folder. If the
 * right-click verb ever stops showing, this still works.
 */
function addToSendTo(exe, args, workingDir) {
  const sendTo = path.join(process.env.APPDATA || '', 'Microsoft', 'Windows', 'SendTo');
  if (!fs.existsSync(sendTo)) return null;

  const link = path.join(sendTo, 'LabUnzip.lnk');
  const ps = `
    $shell = New-Object -ComObject WScript.Shell
    $link = $shell.CreateShortcut('${link.replace(/'/g, "''")}')
    $link.TargetPath = '${exe.replace(/'/g, "''")}'
    $link.Arguments = '${args.replace(/'/g, "''")}'
    $link.WorkingDirectory = '${workingDir.replace(/'/g, "''")}'
    $link.IconLocation = '${exe.replace(/'/g, "''")}'
    $link.Save()
  `;
  execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command', ps], {
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  return link;
}

/**
 * Prefers a built exe if one exists, otherwise wires up the dev command so the
 * menu entry works before packaging.
 */
function resolveCommand() {
  const root = path.resolve(__dirname, '..');

  const packaged = [
    path.join(root, 'dist', 'win-unpacked', 'LabUnzip.exe'),
    path.join(process.env.LOCALAPPDATA || '', 'Programs', 'LabUnzip', 'LabUnzip.exe'),
  ].find((p) => p && fs.existsSync(p));

  if (packaged) {
    return {
      command: `"${packaged}" "%1"`,
      folderCommand: `"${packaged}" --fix-dates "%1"`,
      icon: packaged, mode: 'packaged', exe: packaged, exeArgs: '', root,
    };
  }

  const electron = path.join(root, 'node_modules', 'electron', 'dist', 'electron.exe');
  if (!fs.existsSync(electron)) {
    throw new Error('Neither a built LabUnzip.exe nor electron was found. Run "npm install" first.');
  }
  return {
    command: `"${electron}" "${root}" "%1"`,
    folderCommand: `"${electron}" "${root}" --fix-dates "%1"`,
    icon: electron,
    mode: 'development',
    exe: electron,
    exeArgs: `"${root}"`,
    root,
  };
}

function main() {
  if (process.platform !== 'win32') {
    console.error('This only applies to Windows.');
    process.exit(1);
  }

  const { command, folderCommand, icon, mode, exe, exeArgs, root } = resolveCommand();

  for (const { key, appliesTo } of targetKeys()) {
    reg(['add', key, '/ve', '/t', 'REG_SZ', '/d', LABEL, '/f']);
    reg(['add', key, '/v', 'Icon', '/t', 'REG_SZ', '/d', icon, '/f']);
    if (appliesTo) reg(['add', key, '/v', 'AppliesTo', '/t', 'REG_SZ', '/d', appliesTo, '/f']);
    reg(['add', `${key}\\command`, '/ve', '/t', 'REG_SZ', '/d', command, '/f']);
    console.log(`  registered: ${key}${appliesTo ? '   (zips only)' : ''}`);
  }

  // Right-click a folder: give the photos in it their scan dates (e.g. a roll copied off the phone).
  const folderKey = 'HKCU\\Software\\Classes\\Directory\\shell\\LabUnzipFixDates';
  reg(['add', folderKey, '/ve', '/t', 'REG_SZ', '/d', 'Fix dates with LabUnzip', '/f']);
  reg(['add', folderKey, '/v', 'Icon', '/t', 'REG_SZ', '/d', icon, '/f']);
  reg(['add', `${folderKey}\\command`, '/ve', '/t', 'REG_SZ', '/d', folderCommand, '/f']);
  console.log(`  registered: ${folderKey}   (folders)`);

  const sendTo = addToSendTo(exe, exeArgs, root);
  if (sendTo) console.log(`  registered: ${sendTo}`);

  console.log(`\n"${LABEL}" is set up for .zip files (${mode} build).`);
  console.log('"Fix dates with LabUnzip" is set up for folders.');
  console.log(`  command: ${command}`);
  console.log('\nTwo ways to reach it:');
  console.log('  - right-click a zip (on Windows 11, under "Show more options")');
  console.log('  - right-click a zip, then "Send to" > LabUnzip');
  console.log('\nThe "Send to" route does not depend on file associations, so it works');
  console.log('even when the direct entry does not.');
  console.log('\nIf neither shows yet, Explorer is caching the old menu:');
  console.log('  taskkill /f /im explorer.exe & start explorer.exe');
  console.log('\nRemove everything again with: npm run unregister');
}

try {
  main();
} catch (err) {
  console.error('Could not register the context menu entry:', err.message);
  process.exit(1);
}
