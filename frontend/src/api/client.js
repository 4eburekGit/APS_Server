// Single fetch-based API client.
// Token is read fresh from localStorage on every call so an updated token
// (after login/register) is picked up immediately.

const TOKEN_KEY = 'aps_token';

export function getToken() {
  return localStorage.getItem(TOKEN_KEY);
}
export function setToken(t) {
  if (t) localStorage.setItem(TOKEN_KEY, t);
  else localStorage.removeItem(TOKEN_KEY);
}

async function handle(res) {
  const ct = res.headers.get('content-type') || '';
  const isJson = ct.includes('application/json');
  const body = isJson ? await res.json().catch(() => null) : await res.text();
  if (!res.ok) {
    const msg = (typeof body === 'string' && body) || (body && body.message) || `HTTP ${res.status}`;
    const err = new Error(msg);
    err.status = res.status;
    throw err;
  }
  return body;
}

function authHeaders() {
  const t = getToken();
  return t ? { Authorization: `Bearer ${t}` } : {};
}

export const api = {
  // ---------- AUTH ----------
  register(username, password) {
    return fetch('/auth/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    }).then(handle);
  },
  // FR#17: 2FA-aware login. totpCode is optional; backend returns 401
  // "TOTP required" if user has 2FA enrolled and code is missing.
  login(username, password, totpCode = null) {
    return fetch('/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password, totpCode }),
    }).then(handle);
  },
  // 2FA enrollment: returns { secret, otpauth }.
  enroll2fa(username, password) {
    return fetch('/auth/2fa/enroll', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    }).then(handle);
  },
  verifyEnroll2fa(username, totpCode) {
    return fetch('/auth/2fa/verify-enroll', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, totpCode }),
    }).then(handle);
  },
  disable2fa(username, password) {
    return fetch('/auth/2fa', {
      method: 'DELETE',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    }).then(handle);
  },
  registerAdmin(username, password) {
    return fetch('/auth/register/admin', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    }).then(handle);
  },
  loginAdmin(username, password) {
    return fetch('/auth/login/admin', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    }).then(handle);
  },
  me() {
    return fetch('/auth/me', { headers: authHeaders() }).then(handle);
  },

  // ---------- FOLDERS ----------
  // FR#23/NFT#24 — sort is server-side. Pass {sort:'name'|'size'|'date',
  // dir:'asc'|'desc'}. Default name asc.
  getRootContent(sort = null, dir = null) {
    return fetch(`/api/files/folders/root/content${qs(sort, dir)}`, { headers: authHeaders() }).then(handle);
  },
  getRootMeta() {
    return fetch('/api/files/folders/root/meta', { headers: authHeaders() }).then(handle);
  },
  getBinContent(sort = null, dir = null) {
    return fetch(`/api/files/folders/bin/content${qs(sort, dir)}`, { headers: authHeaders() }).then(handle);
  },
  getBinMeta() {
    return fetch('/api/files/folders/bin/meta', { headers: authHeaders() }).then(handle);
  },
  getFolderContent(folderId, sort = null, dir = null) {
    return fetch(`/api/files/folders/${folderId}/content${qs(sort, dir)}`, { headers: authHeaders() }).then(handle);
  },
  getFolderMeta(folderId) {
    return fetch(`/api/files/folders/${folderId}/meta`, { headers: authHeaders() }).then(handle);
  },
  createFolder(name, parentFolderId = null) {
    return fetch('/api/files/folders/create', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...authHeaders() },
      body: JSON.stringify({ name, parentFolderId }),
    }).then(handle);
  },
  deleteFolder(folderId) {
    return fetch(`/api/files/folders/${folderId}`, {
      method: 'DELETE',
      headers: authHeaders(),
    }).then(handle);
  },
  purgeFolder(folderId) {
    return fetch(`/api/files/folders/${folderId}/purge`, {
      method: 'DELETE',
      headers: authHeaders(),
    }).then(handle);
  },

  // ---------- FILES ----------
  // abortRef (optional) is mutated in place: abortRef.current = xhr. Caller
  // calls abortRef.current.abort() to cancel the upload mid-stream.
  uploadToRoot(file, onProgress, abortRef) {
    return uploadXhr('/api/files/upload', file, onProgress, 'POST', abortRef);
  },
  uploadTo(folderId, file, onProgress, abortRef) {
    return uploadXhr(`/api/files/upload/to/${folderId}`, file, onProgress, 'POST', abortRef);
  },
  // FR#1 (folder upload) + FR#20 (drag-and-drop). Pass File[] each carrying
  // a `relPath` field (slash-joined relative path the user dropped). Server
  // creates intermediate folders.
  uploadTreeToRoot(filesWithRel, onProgress, abortRef) {
    return uploadTreeXhr('/api/files/upload-tree', filesWithRel, onProgress, abortRef);
  },
  uploadTreeTo(folderId, filesWithRel, onProgress, abortRef) {
    return uploadTreeXhr(`/api/files/upload-tree/to/${folderId}`, filesWithRel, onProgress, abortRef);
  },
  updateFile(fileId, file, onProgress) {
    return uploadXhr(`/api/files/${fileId}`, file, onProgress, 'PUT');
  },
  fileMeta(fileId) {
    return fetch(`/api/files/${fileId}/meta`, { headers: authHeaders() }).then(handle);
  },
  downloadUrl(fileId) {
    // For direct <a href>; token is needed — see triggerDownload below
    return `/api/files/${fileId}`;
  },
  async triggerDownload(fileId, filename = 'download') {
    const res = await fetch(`/api/files/${fileId}`, { headers: authHeaders() });
    if (!res.ok) throw new Error(`Не удалось скачать файл (HTTP ${res.status})`);
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  },
  deleteFile(fileId) {
    return fetch(`/api/files/${fileId}`, {
      method: 'DELETE',
      headers: authHeaders(),
    }).then(handle);
  },
  purgeFile(fileId) {
    return fetch(`/api/files/${fileId}/purge`, {
      method: 'DELETE',
      headers: authHeaders(),
    }).then(handle);
  },
  // ---------- File operations (rename / move / copy / restore) ----------
  renameFile(fileId, newName) {
    // Backend takes @RequestBody String — send raw text/plain, NOT
    // JSON.stringify (that would wrap the value in quotes and Spring's
    // StringDecoder would store the quotes literally in the filename).
    return fetch(`/api/files/${fileId}/rename`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'text/plain', ...authHeaders() },
      body: newName,
    }).then(handle);
  },
  moveFile(fileId, targetFolderId /* UUID | null */) {
    return fetch(`/api/files/${fileId}/move`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'text/plain', ...authHeaders() },
      body: targetFolderId == null ? '' : String(targetFolderId),
    }).then(handle);
  },
  copyFile(fileId, targetFolderId /* UUID | null */) {
    return fetch(`/api/files/${fileId}/copy`, {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain', ...authHeaders() },
      body: targetFolderId == null ? '' : String(targetFolderId),
    }).then(handle);
  },
  restoreFile(fileId) {
    return fetch(`/api/files/${fileId}/restore`, {
      method: 'POST',
      headers: authHeaders(),
    }).then(handle);
  },
  // ---------- Folder operations (rename / move / copy / restore) ----------
  renameFolder(folderId, newName) {
    return fetch(`/api/files/folders/${folderId}/rename`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'text/plain', ...authHeaders() },
      body: newName,
    }).then(handle);
  },
  restoreFolder(folderId) {
    return fetch(`/api/files/folders/${folderId}/restore`, {
      method: 'POST',
      headers: authHeaders(),
    }).then(handle);
  },
  moveFolder(folderId, newParentId /* UUID | null */) {
    return fetch(`/api/files/folders/${folderId}/move`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'text/plain', ...authHeaders() },
      body: newParentId == null ? '' : String(newParentId),
    }).then(handle);
  },
  copyFolder(folderId, newParentId /* UUID | null */) {
    return fetch(`/api/files/folders/${folderId}/copy`, {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain', ...authHeaders() },
      body: newParentId == null ? '' : String(newParentId),
    }).then(handle);
  },

  // ---------- FOLDER DOWNLOAD AS ZIP (FR#1) ----------
  async triggerDownloadFolder(folderId, name = 'folder') {
    const res = await fetch(`/api/files/folders/${folderId}/download.zip`, { headers: authHeaders() });
    if (!res.ok) throw new Error(`Не удалось скачать папку (HTTP ${res.status})`);
    const blob = await res.blob();
    const blobUrl = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = blobUrl; a.download = `${name}.zip`;
    document.body.appendChild(a); a.click(); a.remove();
    URL.revokeObjectURL(blobUrl);
  },

  // ---------- USER STATS (FR#18, NFT#7, NFT#21) ----------
  myStats() {
    return fetch('/api/user/stats', { headers: authHeaders() }).then(handle);
  },
  // Self-info: needed by Profile page to know if 2FA is already enrolled.
  myInfo() {
    return fetch('/api/user/me', { headers: authHeaders() }).then(handle);
  },

  // ---------- CSV EXPORT (FR#19, NFT#8) ----------
  async exportFolderCsv(folderId /* UUID | null = root */, filename = 'folder.csv') {
    const url = folderId == null
        ? '/api/files/folders/root/export.csv'
        : `/api/files/folders/${folderId}/export.csv`;
    const res = await fetch(url, { headers: authHeaders() });
    if (!res.ok) throw new Error(`Не удалось экспортировать (HTTP ${res.status})`);
    const blob = await res.blob();
    const blobUrl = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = blobUrl; a.download = filename;
    document.body.appendChild(a); a.click(); a.remove();
    URL.revokeObjectURL(blobUrl);
  },

  // ---------- TAGS (FR#20, NFT#9) ----------
  listTags() {
    return fetch('/api/tags', { headers: authHeaders() }).then(handle);
  },
  createTag(name, color = null) {
    return fetch('/api/tags', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...authHeaders() },
      body: JSON.stringify({ name, color }),
    }).then(handle);
  },
  deleteTag(tagId) {
    return fetch(`/api/tags/${tagId}`, { method: 'DELETE', headers: authHeaders() }).then(handle);
  },
  attachTag(tagId, fileId) {
    return fetch(`/api/tags/${tagId}/files/${fileId}`, { method: 'POST', headers: authHeaders() }).then(handle);
  },
  detachTag(tagId, fileId) {
    return fetch(`/api/tags/${tagId}/files/${fileId}`, { method: 'DELETE', headers: authHeaders() }).then(handle);
  },
  filesForTag(tagId) {
    return fetch(`/api/tags/${tagId}/files`, { headers: authHeaders() }).then(handle);
  },
  tagsForFile(fileId) {
    return fetch(`/api/tags/files/${fileId}`, { headers: authHeaders() }).then(handle);
  },

  // ---------- SEARCH (FR#15, NFT#4) ----------
  searchFiles({ q, type, from, to, limit } = {}) {
    const p = new URLSearchParams();
    if (q)    p.set('q', q);
    if (type) p.set('type', type);
    if (from) p.set('from', from);
    if (to)   p.set('to', to);
    if (limit) p.set('limit', String(limit));
    const qs = p.toString();
    return fetch(`/api/files/search${qs ? '?' + qs : ''}`, { headers: authHeaders() }).then(handle);
  },

  // ---------- PREVIEW (FR#16, NFT#5) ----------
  // Returns a blob URL the caller can drop into <img src="...">. Caller
  // should URL.revokeObjectURL when done. Throws on 415 (unsupported type).
  async previewBlobUrl(fileId, size = 512) {
    const res = await fetch(`/api/files/${fileId}/preview?size=${size}`, { headers: authHeaders() });
    if (!res.ok) {
      let msg = `HTTP ${res.status}`;
      try { msg = (await res.text()) || msg; } catch {}
      const err = new Error(msg); err.status = res.status; throw err;
    }
    const blob = await res.blob();
    return URL.createObjectURL(blob);
  },
  // Text preview (json/txt/log/code). Returns {content, truncated, size, contentType}.
  // 415 on binary/unknown types.
  previewText(fileId, max = 262144) {
    return fetch(`/api/files/${fileId}/preview-text?max=${max}`, { headers: authHeaders() }).then(handle);
  },

  // ---------- COMMENTS (FR#14, NFT#6) ----------
  listComments(fileId) {
    return fetch(`/api/files/${fileId}/comments`, { headers: authHeaders() }).then(handle);
  },
  addComment(fileId, body) {
    return fetch(`/api/files/${fileId}/comments`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...authHeaders() },
      body: JSON.stringify({ body }),
    }).then(handle);
  },
  deleteComment(fileId, commentId) {
    return fetch(`/api/files/${fileId}/comments/${commentId}`, { method: 'DELETE', headers: authHeaders() }).then(handle);
  },

  // ---------- AUDIT (FR#22) ----------
  fileAudit(fileId) {
    return fetch(`/api/files/${fileId}/audit`, { headers: authHeaders() }).then(handle);
  },

  // ---------- ADMIN (ROLE_ADMIN required) ----------
  listUsers() {
    return fetch('/admin/users', { headers: authHeaders() }).then(handle);
  },
  getUser(userId) {
    return fetch(`/admin/users/${userId}`, { headers: authHeaders() }).then(handle);
  },
  deleteUser(userId) {
    return fetch(`/admin/users/${userId}`, {
      method: 'DELETE',
      headers: authHeaders(),
    }).then(handle);
  },
};

// Build ?sort=...&dir=... query string. Empty string when both null so the
// existing endpoint stays untouched.
function qs(sort, dir) {
  const p = new URLSearchParams();
  if (sort) p.set('sort', sort);
  if (dir) p.set('dir', dir);
  const s = p.toString();
  return s ? `?${s}` : '';
}

// Multi-file upload with relative paths. Browsers' webkitRelativePath
// attribute is read on the caller side; we just write each rel-path into
// the part filename so server can split on '/'.
function uploadTreeXhr(url, filesWithRel, onProgress, abortRef) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', url);
    const t = getToken();
    if (t) xhr.setRequestHeader('Authorization', `Bearer ${t}`);
    if (abortRef) abortRef.current = xhr;
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && onProgress) onProgress(e.loaded / e.total);
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        try { resolve(JSON.parse(xhr.responseText)); }
        catch { resolve(xhr.responseText); }
      } else {
        const err = new Error(xhr.responseText || `HTTP ${xhr.status}`);
        err.status = xhr.status;
        reject(err);
      }
    };
    xhr.onerror = () => reject(new Error('Сетевая ошибка'));
    xhr.onabort = () => {
      const err = new Error('Загрузка отменена');
      err.aborted = true;
      reject(err);
    };
    const fd = new FormData();
    for (const { file, relPath } of filesWithRel) {
      // 3rd FormData.append arg overrides the part filename; Chrome and
      // Firefox 78+ keep slashes intact when sending.
      fd.append('files', file, relPath || file.name);
    }
    xhr.send(fd);
  });
}

/**
 * Single-file upload with abortable promise.
 * Caller may pass an {abortRef} object — we set abortRef.current = xhr so
 * the UI can call xhr.abort() to cancel the in-flight upload.
 */
function uploadXhr(url, file, onProgress, method = 'POST', abortRef) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open(method, url);
    const t = getToken();
    if (t) xhr.setRequestHeader('Authorization', `Bearer ${t}`);
    if (abortRef) abortRef.current = xhr;
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && onProgress) onProgress(e.loaded / e.total);
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        try { resolve(JSON.parse(xhr.responseText)); }
        catch { resolve(xhr.responseText); }
      } else {
        const err = new Error(xhr.responseText || `HTTP ${xhr.status}`);
        err.status = xhr.status;
        reject(err);
      }
    };
    xhr.onerror = () => reject(new Error('Сетевая ошибка'));
    xhr.onabort = () => {
      const err = new Error('Загрузка отменена');
      err.aborted = true;
      reject(err);
    };
    const fd = new FormData();
    fd.append('file', file);
    xhr.send(fd);
  });
}
