'use strict';

const path = require('path');
const { applyPattern, sanitiseFolderName } = require('./naming');

/**
 * The plan is what the app shows before it touches the destination: every
 * folder it will create and every file it will write, with the names already
 * applied. Nothing here writes anything - `buildOutputPlan` is pure, so the
 * preview and the actual run cannot drift apart.
 */

/** Walks every folder node in a scan tree, depth first. */
function walkFolders(node, visit, depth = 0) {
  visit(node, depth);
  for (const child of node.folders) walkFolders(child, visit, depth + 1);
}

/**
 * Builds the editable draft from a scan: one row per folder, pre-filled with
 * the lab's own names so doing nothing is a valid choice.
 */
function planFrom(scan) {
  const folders = [];
  walkFolders(scan.root, (node, depth) => {
    folders.push({
      id: node.id,
      path: node.path,
      depth,
      sourceName: node.name,
      outputName: node.name,
      fileCount: node.fileCount,
      totalFileCount: node.totalFileCount,
      totalBytes: node.totalBytes,
      dateSources: node.dateSources,
      undatedCount: node.undatedCount,
      pattern: { prefix: '', suffix: '', separator: '_' },
      metadata: {},
    });
  });
  return { folders, destination: '' };
}

function indexDraft(draft) {
  const byPath = new Map();
  for (const folder of draft.folders) byPath.set(folder.path, folder);
  return byPath;
}

/**
 * Resolves the output path for a folder by walking its renamed ancestors.
 * A folder renamed to nothing collapses away, so files move up a level - that
 * is how you extract straight into the destination without a wrapper folder.
 */
function outputSegments(node, byPath, ancestors) {
  const segments = [];
  for (const ancestor of [...ancestors, node]) {
    const draft = byPath.get(ancestor.path);
    const raw = draft ? draft.outputName : ancestor.name;
    if (raw === null || raw === undefined || String(raw).trim() === '') continue;
    segments.push(sanitiseFolderName(raw, ancestor.name));
  }
  return segments;
}

/**
 * @param {object} scan        result of scanArchive
 * @param {object} draft       edited plan from planFrom
 * @param {string} destination absolute folder the user picked
 * @returns {{files: Array, folders: string[], conflicts: Array, totalBytes: number}}
 */
function buildOutputPlan(scan, draft, destination) {
  const byPath = indexDraft(draft);
  const files = [];
  const folderSet = new Set();
  const takenPaths = new Map();
  const conflicts = [];

  const visit = (node, ancestors) => {
    const segments = outputSegments(node, byPath, ancestors);
    const folderPath = path.join(destination, ...segments);
    if (node.fileCount > 0 || node.folders.length === 0) folderSet.add(folderPath);

    const folderDraft = byPath.get(node.path);
    const pattern = (folderDraft && folderDraft.pattern) || {};
    const metadata = (folderDraft && folderDraft.metadata) || {};

    for (const file of node.files) {
      const outputName = applyPattern(file.originalName, pattern);
      const targetPath = path.join(folderPath, outputName);
      const key = targetPath.toLowerCase(); // Windows is case-insensitive

      if (takenPaths.has(key)) {
        conflicts.push({
          targetPath,
          sources: [takenPaths.get(key), file.entryPath],
        });
      } else {
        takenPaths.set(key, file.entryPath);
      }

      files.push({
        sourcePath: file.entryPath,
        origin: file.origin,
        targetPath,
        folderPath,
        originalName: file.originalName,
        outputName,
        size: file.size,
        date: file.date,
        dateSource: file.dateSource,
        dateSourceLabel: file.dateSourceLabel,
        metadata: Object.keys(metadata).length ? { ...metadata } : null,
      });
    }

    for (const child of node.folders) visit(child, [...ancestors, node]);
  };

  visit(scan.root, []);

  return {
    files,
    folders: [...folderSet].sort(),
    conflicts,
    totalBytes: files.reduce((sum, f) => sum + f.size, 0),
    undatedCount: files.filter((f) => !f.date).length,
  };
}

module.exports = { planFrom, buildOutputPlan, walkFolders };
