'use strict';

/** Removes the "Unpack with LabUnzip" right-click entry from every place it was added. */

const { execFileSync } = require('child_process');

function reg(args) {
  return execFileSync('reg.exe', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
}

function candidateKeys() {
  const keys = [
    'HKCU\\Software\\Classes\\SystemFileAssociations\\compressed\\shell\\LabUnzip',
    'HKCU\\Software\\Classes\\SystemFileAssociations\\.zip\\shell\\LabUnzip',
    'HKCU\\Software\\Classes\\Directory\\shell\\LabUnzipFixDates',
    // No longer registered, but older installs may still have it.
    'HKCU\\Software\\Classes\\*\\shell\\LabUnzip',
  ];
  const progIds = new Set(['CompressedFolder']);
  for (const query of [
    ['query', 'HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Explorer\\FileExts\\.zip\\UserChoice', '/v', 'ProgId'],
    ['query', 'HKCR\\.zip', '/ve'],
  ]) {
    try {
      const match = reg(query).match(/REG_SZ\s+(\S+)/);
      if (match) progIds.add(match[1]);
    } catch { /* nothing registered there */ }
  }
  for (const progId of progIds) keys.push(`HKCU\\Software\\Classes\\${progId}\\shell\\LabUnzip`);
  return keys;
}

if (process.platform !== 'win32') {
  console.error('This only applies to Windows.');
  process.exit(1);
}

let removed = 0;
for (const key of candidateKeys()) {
  try {
    reg(['delete', key, '/f']);
    console.log(`Removed ${key}`);
    removed += 1;
  } catch (err) {
    const message = String(err.stderr || err.message);
    if (!/unable to find|cannot find/i.test(message)) {
      console.error(`Could not remove ${key}: ${message.trim()}`);
    }
  }
}

// The "Send to" entry is a shortcut in a folder, not a registry key.
const path = require('path');
const fs = require('fs');
const sendToLink = path.join(process.env.APPDATA || '', 'Microsoft', 'Windows', 'SendTo', 'LabUnzip.lnk');
if (fs.existsSync(sendToLink)) {
  try {
    fs.unlinkSync(sendToLink);
    console.log(`Removed ${sendToLink}`);
    removed += 1;
  } catch (err) {
    console.error(`Could not remove ${sendToLink}: ${err.message}`);
  }
}

console.log(removed ? `\nRemoved ${removed} entr${removed === 1 ? 'y' : 'ies'}.` : 'Nothing to remove.');
console.log('The Desktop shortcut, if you made one, is left alone - delete it like any file.');
