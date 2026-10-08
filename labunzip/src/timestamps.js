'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFile } = require('child_process');

/**
 * Stamping files with the right date.
 *
 * Node can set modified and accessed times, but not the Windows "created"
 * date - and created is the one File Explorer sorts by and the one that was
 * showing the download date. So created is set through PowerShell.
 *
 * Rather than spawn PowerShell per file, the whole batch is written to a JSON
 * file and handed over in one go.
 */

/** Sets modified + accessed. Works everywhere, no subprocess. */
function setModified(filePath, date) {
  fs.utimesSync(filePath, date, date);
}

/**
 * Sets the Windows creation date for many files in a single PowerShell call.
 * @param {Array<{path: string, date: Date}>} entries
 * @returns {Promise<{updated: number, failed: Array<{path: string, error: string}>}>}
 */
function setCreatedBatch(entries) {
  if (process.platform !== 'win32' || entries.length === 0) {
    return Promise.resolve({ updated: 0, failed: [], skipped: true });
  }

  const listFile = path.join(os.tmpdir(), `labunzip-times-${process.pid}-${Date.now()}.json`);
  const payload = entries.map((e) => ({ Path: e.path, Date: e.date.toISOString() }));
  fs.writeFileSync(listFile, JSON.stringify(payload), 'utf8');

  // -Encoding UTF8 so paths with accents survive the handoff.
  const script = `
    $ErrorActionPreference = 'Continue'
    $items = Get-Content -LiteralPath '${listFile.replace(/'/g, "''")}' -Raw -Encoding UTF8 | ConvertFrom-Json
    $failed = @()
    foreach ($item in $items) {
      try {
        $when = [DateTime]::Parse($item.Date, [System.Globalization.CultureInfo]::InvariantCulture, [System.Globalization.DateTimeStyles]::RoundtripKind)
        $file = Get-Item -LiteralPath $item.Path -Force
        $file.CreationTime = $when
        $file.LastWriteTime = $when
      } catch {
        $failed += [pscustomobject]@{ path = $item.Path; error = $_.Exception.Message }
      }
    }
    [pscustomobject]@{ updated = ($items.Count - $failed.Count); failed = $failed } | ConvertTo-Json -Depth 4 -Compress
  `;

  return new Promise((resolve) => {
    execFile(
      'powershell.exe',
      ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command', script],
      { maxBuffer: 32 * 1024 * 1024 },
      (err, stdout) => {
        try { fs.unlinkSync(listFile); } catch { /* best effort */ }
        if (err) return resolve({ updated: 0, failed: entries.map((e) => ({ path: e.path, error: err.message })) });
        try {
          const parsed = JSON.parse(String(stdout).trim() || '{}');
          const failed = parsed.failed ? [].concat(parsed.failed) : [];
          resolve({ updated: parsed.updated || 0, failed });
        } catch {
          resolve({ updated: entries.length, failed: [] });
        }
      },
    );
  });
}

module.exports = { setModified, setCreatedBatch };
