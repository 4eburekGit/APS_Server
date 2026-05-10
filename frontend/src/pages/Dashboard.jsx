import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api } from '../api/client.js';
import { useToast } from '../context/ToastContext.jsx';
import Icon from '../components/Icon.jsx';
import FileIcon from '../components/FileIcon.jsx';
import Modal from '../components/Modal.jsx';
import Breadcrumbs from '../components/Breadcrumbs.jsx';
import PreviewModal from '../components/PreviewModal.jsx';

const fmtSize = (b) => {
  if (b == null) return '—';
  const u = ['Б', 'КБ', 'МБ', 'ГБ', 'ТБ'];
  let i = 0; let n = Number(b);
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
  return `${n < 10 ? n.toFixed(1) : Math.round(n)} ${u[i]}`;
};
const fmtDate = (s) => {
  if (!s) return '—';
  try {
    return new Date(s).toLocaleString('ru-RU', { day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
  } catch { return s; }
};

/** Pick legible text colour for an arbitrary background hex.
 *  Uses W3C relative-luminance threshold of 0.55 (slightly above 0.5
 *  so mid-bright accent colours still get dark text). */
function pickTextOn(bg) {
  if (!bg || typeof bg !== 'string') return 'dark';
  const m = bg.replace('#', '').match(/^([0-9a-f]{6})$/i);
  if (!m) return 'dark';
  const hex = m[1];
  const r = parseInt(hex.slice(0, 2), 16) / 255;
  const g = parseInt(hex.slice(2, 4), 16) / 255;
  const b = parseInt(hex.slice(4, 6), 16) / 255;
  // Quick luminance approximation
  const lum = 0.2126 * r + 0.7152 * g + 0.0722 * b;
  return lum > 0.6 ? 'dark' : 'light';
}

/** Render up to 3 tag mini-chips + "+N" overflow indicator. */
function TagMiniRow({ tags }) {
  if (!tags || tags.length === 0) return null;
  const visible = tags.slice(0, 3);
  const extra = tags.length - visible.length;
  return (
    <div className="tag-mini-row">
      {visible.map((t) => {
        const bg = t.color || '#eef0ff';
        const cls = pickTextOn(bg) === 'dark' ? 'dark-text' : 'light-text';
        return (
          <span key={t.id} className={`tag-mini ${cls}`} style={{ background: bg }} title={t.name}>
            {t.name}
          </span>
        );
      })}
      {extra > 0 && <span className="tag-mini-more">+{extra}</span>}
    </div>
  );
}

// Human-readable type. Falls back to file extension when the server gives
// a useless contentType (octet-stream / missing), then to the raw MIME.
const EXT_LABEL = {
  pdf: 'PDF', doc: 'Word', docx: 'Word', xls: 'Excel', xlsx: 'Excel',
  ppt: 'PowerPoint', pptx: 'PowerPoint', txt: 'Текст', md: 'Markdown',
  csv: 'CSV', json: 'JSON', xml: 'XML', yml: 'YAML', yaml: 'YAML',
  html: 'HTML', htm: 'HTML', css: 'CSS', js: 'JavaScript', ts: 'TypeScript',
  jsx: 'React', tsx: 'React', java: 'Java', py: 'Python', go: 'Go',
  rs: 'Rust', c: 'C', cpp: 'C++', h: 'C header', cs: 'C#', sh: 'Shell',
  sql: 'SQL', db: 'База данных', sqlite: 'SQLite',
  png: 'PNG', jpg: 'JPEG', jpeg: 'JPEG', gif: 'GIF', webp: 'WebP', svg: 'SVG', bmp: 'BMP', ico: 'Иконка',
  mp3: 'MP3', wav: 'WAV', flac: 'FLAC', ogg: 'OGG', m4a: 'M4A',
  mp4: 'MP4', mkv: 'MKV', mov: 'MOV', webm: 'WebM', avi: 'AVI',
  zip: 'ZIP', rar: 'RAR', '7z': '7-Zip', tar: 'TAR', gz: 'GZip', bz2: 'BZip2',
  iso: 'ISO образ', dmg: 'DMG образ', exe: 'Windows EXE', msi: 'Windows MSI',
  apk: 'Android APK', deb: 'Debian пакет', rpm: 'RPM пакет',
  conf: 'Конфиг', cfg: 'Конфиг', ini: 'INI', env: 'ENV', log: 'Лог',
  ttf: 'Шрифт', otf: 'Шрифт', woff: 'Шрифт', woff2: 'Шрифт',
};
// Mask backend system-folder names (root_<uuid>, bin_<uuid>) so they
// never leak into the UI. We otherwise saw them flash as page-title /
// breadcrumb leaf during navigation between folders.
const ROOT_NAME_RE = /^root_[0-9a-f-]{8,}$/i;
const BIN_NAME_RE  = /^bin_[0-9a-f-]{8,}$/i;
const displayFolderName = (name) => {
  if (!name) return '';
  if (ROOT_NAME_RE.test(name)) return 'Мои файлы';
  if (BIN_NAME_RE.test(name))  return 'Корзина';
  return name;
};

const prettyType = (ct, filename) => {
  const ext = (filename || '').split('.').pop()?.toLowerCase();
  if (ext && EXT_LABEL[ext]) return EXT_LABEL[ext];
  if (!ct || ct === 'application/octet-stream') return ext ? ext.toUpperCase() : 'Файл';
  // Trim common verbose MIMEs
  if (ct.startsWith('image/')) return ct.slice(6).toUpperCase();
  if (ct.startsWith('video/')) return ct.slice(6).toUpperCase();
  if (ct.startsWith('audio/')) return ct.slice(6).toUpperCase();
  if (ct.startsWith('text/'))  return ct.slice(5).toUpperCase();
  if (ct === 'application/pdf') return 'PDF';
  if (ct === 'application/json') return 'JSON';
  if (ct === 'application/zip') return 'ZIP';
  return ct;
};

export default function Dashboard({ view }) {
  const { folderId } = useParams();
  const navigate = useNavigate();
  const toast = useToast();

  const [content, setContent] = useState({ currentFolder: null, subFolders: [], files: [] });
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [layout, setLayout] = useState('grid'); // 'grid' | 'list'
  // FR#23/NFT#24 — server-side sort. Persisted in localStorage so the
  // user's preference survives a refresh.
  const [sortField, setSortField] = useState(() => localStorage.getItem('aps_sort') || 'name');
  const [sortDir, setSortDir] = useState(() => localStorage.getItem('aps_dir') || 'asc');
  useEffect(() => { localStorage.setItem('aps_sort', sortField); }, [sortField]);
  useEffect(() => { localStorage.setItem('aps_dir', sortDir); }, [sortDir]);
  const [selected, setSelected] = useState(null);
  const [menu, setMenu] = useState(null); // {x,y,target}
  // After the menu mounts, measure it. If anchoring with the menu's left
  // edge at the click point would overflow the viewport, flip the anchor
  // so the menu's RIGHT edge lands on the click point (i.e. opens leftward).
  // Same flip for vertical when near the bottom. This stops text wrapping
  // because the menu's width always fits onscreen at its natural size.
  const menuRef = useRef(null);
  useLayoutEffect(() => {
    if (!menu || !menuRef.current) return;
    const el = menuRef.current;
    const r = el.getBoundingClientRect();
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    const margin = 8;
    let nx = menu.x, ny = menu.y;
    // Horizontal: would right edge spill? Anchor right edge to click.
    if (menu.x + r.width > vw - margin) {
      nx = Math.max(margin, menu.x - r.width);
    }
    // Vertical: same flip — menu opens upward.
    if (menu.y + r.height > vh - margin) {
      ny = Math.max(margin, menu.y - r.height);
    }
    if (nx !== menu.x || ny !== menu.y) {
      el.style.left = `${nx}px`;
      el.style.top  = `${ny}px`;
    }
  }, [menu]);
  const [uploads, setUploads] = useState([]); // [{id, name, progress, status}]
  const [showCreate, setShowCreate] = useState(false);
  const [folderName, setFolderName] = useState('');
  const [renameTarget, setRenameTarget] = useState(null);
  const [renameValue, setRenameValue] = useState('');
  const [pickerMode, setPickerMode] = useState(null); // 'move' | 'copy' | null
  const [pickerTarget, setPickerTarget] = useState(null);
  // FR#16 + FR#14/20/22 — universal preview modal (also embeds the
  // tags/comments/audit panel on the right side).
  const [previewFile, setPreviewFile] = useState(null);
  // FR#20 — { fileId: [tagEntity, ...] } for inline mini-chips on cards/rows.
  // Populated after each load() via batched api.tagsForFile.
  const [tagsByFile, setTagsByFile] = useState({});

  const dropRef = useRef(null);
  const [dragOver, setDragOver] = useState(false);
  const fileInput = useRef(null);
  const folderInput = useRef(null);

  const isTrashView = view === 'trash';
  const isRoot = view === 'root';
  // We're inside the trash *tree* if either we're at the trash root, or we've
  // navigated into a folder that itself is soft-deleted. The whole binned
  // folder lives under bin_<uid> as a single unit (we don't unpack on delete),
  // so once we're inside one we treat everything as trash regardless of each
  // individual row's deletedAt flag.
  const inTrashTree = isTrashView || !!content?.currentFolder?.deletedAt;
  const isTrash = inTrashTree;

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = isRoot
        ? await api.getRootContent(sortField, sortDir)
        : isTrashView
          // Bin's direct children = everything the user trashed at top level
          // (individual files + whole folders). Backend lists them via the
          // bin folder's id, not via a deletedAt scan.
          ? await api.getBinContent(sortField, sortDir)
          : await api.getFolderContent(folderId, sortField, sortDir);
      setContent(data || { currentFolder: null, subFolders: [], files: [] });
    } catch (e) {
      toast.error(e.message || 'Не удалось загрузить содержимое');
    } finally { setLoading(false); }
  }, [folderId, isRoot, isTrashView, toast, sortField, sortDir]);

  useEffect(() => { load(); }, [load]);
  useEffect(() => { setSelected(null); setMenu(null); }, [folderId, view]);

  // After folder content loads, batch-fetch tags for each file so we can
  // render mini-chips inline on cards and table rows. Concurrency is
  // implicit via Promise.all — server handles ≤1000 files per folder per
  // NFT#3, and tag lookup hits a (file_id, tag_id) PK index.
  useEffect(() => {
    let cancelled = false;
    const files = content?.files || [];
    if (files.length === 0) { setTagsByFile({}); return () => {}; }
    Promise.all(files.map((f) =>
      api.tagsForFile(f.id)
        .then((tags) => [f.id, tags || []])
        .catch(() => [f.id, []])
    )).then((entries) => {
      if (!cancelled) setTagsByFile(Object.fromEntries(entries));
    });
    return () => { cancelled = true; };
  }, [content]);

  // Close ctx menu
  useEffect(() => {
    const onClick = () => setMenu(null);
    const onKey = (e) => { if (e.key === 'Escape') { setMenu(null); setSelected(null); } };
    window.addEventListener('click', onClick);
    window.addEventListener('keydown', onKey);
    return () => { window.removeEventListener('click', onClick); window.removeEventListener('keydown', onKey); };
  }, []);

  // Filtered lists.
  // In the trash tree we show everything the backend returned: at the trash
  // root that's bin's children (all of which are top-level binned items), and
  // inside a binned folder that's the original contents (which kept their
  // deletedAt = NULL because we move folders as a unit). In the live tree we
  // hide rows with deletedAt set as a defensive backstop.
  const visibleFolders = useMemo(() => {
    const list = content.subFolders || [];
    return list
      .filter((f) => isTrash ? true : !f.deletedAt)
      .filter((f) => f.name?.toLowerCase().includes(search.toLowerCase()));
  }, [content, search, isTrash]);

  const visibleFiles = useMemo(() => {
    const list = content.files || [];
    return list
      .filter((f) => isTrash ? true : !f.deletedAt)
      .filter((f) => f.filename?.toLowerCase().includes(search.toLowerCase()));
  }, [content, search, isTrash]);

  // ----- upload -----
  const targetFolderId = content?.currentFolder?.id;
  const startUpload = useCallback((files) => {
    if (isTrash) { toast.warn('Нельзя загружать в корзину'); return; }
    Array.from(files).forEach((file) => {
      const id = Math.random().toString(36).slice(2);
      // abortRef is passed by reference into the api layer; the XHR sets
      // abortRef.current = xhr so the tray's Cancel button can abort.
      const abortRef = { current: null };
      setUploads((u) => [...u, { id, name: file.name, progress: 0, status: 'uploading', abortRef }]);

      const onProg = (p) => setUploads((u) => u.map((x) => x.id === id ? { ...x, progress: p } : x));
      const promise = isRoot || !targetFolderId
        ? api.uploadToRoot(file, onProg, abortRef)
        : api.uploadTo(targetFolderId, file, onProg, abortRef);

      promise
        .then(() => {
          setUploads((u) => u.map((x) => x.id === id ? { ...x, status: 'done', progress: 1 } : x));
          toast.success(`Загружено: ${file.name}`);
          load();
          setTimeout(() => setUploads((u) => u.filter((x) => x.id !== id)), 2500);
        })
        .catch((err) => {
          if (err.aborted) {
            setUploads((u) => u.map((x) => x.id === id ? { ...x, status: 'aborted' } : x));
            toast.info(`Отменено: ${file.name}`);
            setTimeout(() => setUploads((u) => u.filter((x) => x.id !== id)), 2000);
            return;
          }
          setUploads((u) => u.map((x) => x.id === id ? { ...x, status: 'error', error: err.message } : x));
          toast.error(`${file.name}: ${err.message}`);
        });
    });
  }, [isRoot, isTrash, targetFolderId, load, toast]);

  // FR#1 (folder upload) + FR#20 (drag-and-drop). Sends one multipart with
  // `files` parts; each part's filename carries the slash-joined relative
  // path so the server can rebuild the tree.
  const startUploadTree = useCallback((filesWithRel) => {
    if (isTrash) { toast.warn('Нельзя загружать в корзину'); return; }
    if (!filesWithRel.length) return;
    const id = Math.random().toString(36).slice(2);
    const totalBytes = filesWithRel.reduce((s, x) => s + (x.file.size || 0), 0);
    const summary = `Папка: ${filesWithRel.length} файл${filesWithRel.length === 1 ? '' : 'ов'} (${fmtSize(totalBytes)})`;
    const abortRef = { current: null };
    setUploads((u) => [...u, { id, name: summary, progress: 0, status: 'uploading', abortRef }]);
    const onProg = (p) => setUploads((u) => u.map((x) => x.id === id ? { ...x, progress: p } : x));
    const promise = (isRoot || !targetFolderId)
      ? api.uploadTreeToRoot(filesWithRel, onProg, abortRef)
      : api.uploadTreeTo(targetFolderId, filesWithRel, onProg, abortRef);
    promise
      .then(() => {
        setUploads((u) => u.map((x) => x.id === id ? { ...x, status: 'done', progress: 1 } : x));
        toast.success(`Загружено файлов: ${filesWithRel.length}`);
        load();
        setTimeout(() => setUploads((u) => u.filter((x) => x.id !== id)), 2500);
      })
      .catch((err) => {
        if (err.aborted) {
          setUploads((u) => u.map((x) => x.id === id ? { ...x, status: 'aborted' } : x));
          toast.info('Загрузка папки отменена');
          setTimeout(() => setUploads((u) => u.filter((x) => x.id !== id)), 2000);
          return;
        }
        setUploads((u) => u.map((x) => x.id === id ? { ...x, status: 'error', error: err.message } : x));
        toast.error(err.message || 'Ошибка загрузки папки');
      });
  }, [isRoot, isTrash, targetFolderId, load, toast]);

  /** Cancel an in-flight upload via its abortRef. Tray Cancel-button hook. */
  const cancelUpload = useCallback((uploadId) => {
    setUploads((u) => {
      const target = u.find((x) => x.id === uploadId);
      if (target?.abortRef?.current && target.status === 'uploading') {
        try { target.abortRef.current.abort(); } catch (_) { /* ignore */ }
      }
      return u;   // status flip happens in promise.catch(aborted)
    });
  }, []);

  /** Walk DataTransferItemList recursively, collect files with relative paths. */
  const collectDroppedTree = (items) => new Promise((resolve) => {
    const out = [];
    let pending = 0;
    const done = () => { if (pending === 0) resolve(out); };
    const walk = (entry, prefix) => {
      if (!entry) { return; }
      if (entry.isFile) {
        pending++;
        entry.file((file) => {
          out.push({ file, relPath: prefix + file.name });
          pending--; done();
        }, () => { pending--; done(); });
      } else if (entry.isDirectory) {
        pending++;
        const reader = entry.createReader();
        const readBatch = () => reader.readEntries((batch) => {
          if (!batch.length) { pending--; done(); return; }
          batch.forEach((e) => walk(e, prefix + entry.name + '/'));
          readBatch(); // chrome chunks at 100; keep reading
        }, () => { pending--; done(); });
        readBatch();
      }
    };
    for (const it of items) {
      const entry = it.webkitGetAsEntry?.();
      if (entry) walk(entry, '');
    }
    if (pending === 0) resolve(out);
  });

  // drag & drop
  useEffect(() => {
    const el = dropRef.current;
    if (!el) return;
    const onDragOver = (e) => { e.preventDefault(); setDragOver(true); };
    const onDragLeave = () => setDragOver(false);
    const onDrop = async (e) => {
      e.preventDefault();
      setDragOver(false);
      const items = e.dataTransfer?.items;
      // If browser supports webkitGetAsEntry, walk the tree (handles dropped
      // folders). Otherwise fall back to a flat list of files.
      if (items && items.length && items[0].webkitGetAsEntry) {
        const tree = await collectDroppedTree(items);
        const hasFolder = tree.some((x) => x.relPath.includes('/'));
        if (hasFolder) {
          startUploadTree(tree);
          return;
        }
        // No subfolders dropped — flat upload via single-file path
        // (preserves per-file progress + tray entries).
        if (tree.length) {
          startUpload(tree.map((x) => x.file));
          return;
        }
      }
      if (e.dataTransfer?.files?.length) startUpload(e.dataTransfer.files);
    };
    el.addEventListener('dragover', onDragOver);
    el.addEventListener('dragleave', onDragLeave);
    el.addEventListener('drop', onDrop);
    return () => {
      el.removeEventListener('dragover', onDragOver);
      el.removeEventListener('dragleave', onDragLeave);
      el.removeEventListener('drop', onDrop);
    };
  }, [startUpload, startUploadTree]);

  // ----- actions -----
  // Default file click → preview modal (shows image/text/placeholder + tags
  // + comments + audit). Folder click → navigate. Files in trash still open
  // the modal (read-only is fine; download is the only meaningful action).
  const handleOpen = (item) => {
    if (item._kind === 'folder') {
      navigate(`/folder/${item.id}`);
    } else {
      setPreviewFile(item);
    }
  };

  const handleDownload = (file) => api.triggerDownload(file.id, file.filename)
    .then(() => toast.success(`Скачано: ${file.filename}`))
    .catch((e) => toast.error(e.message));

  const handleSoftDelete = async (item) => {
    try {
      if (item._kind === 'folder') await api.deleteFolder(item.id);
      else await api.deleteFile(item.id);
      toast.success('Перемещено в корзину');
      load();
    } catch (e) { toast.error(e.message); }
  };

  const handlePurge = async (item) => {
    if (!confirm(`Удалить «${item._kind === 'folder' ? item.name : item.filename}» навсегда?`)) return;
    try {
      if (item._kind === 'folder') await api.purgeFolder(item.id);
      else await api.purgeFile(item.id);
      toast.success('Удалено навсегда');
      load();
    } catch (e) { toast.error(e.message); }
  };

  const handleRestore = async (item) => {
    try {
      if (item._kind === 'folder') {
        await api.restoreFolder(item.id);
        toast.success(`Папка «${item.name}» восстановлена`);
      } else {
        await api.restoreFile(item.id);
        toast.success(`Файл «${item.filename}» восстановлен`);
      }
      load();
    } catch (e) { toast.error(e.message || 'Не удалось восстановить'); }
  };

  const handleRename = async () => {
    if (!renameTarget || !renameValue.trim()) return;
    const isFolder = renameTarget._kind === 'folder';
    try {
      if (isFolder) {
        await api.renameFolder(renameTarget.id, renameValue.trim());
        toast.success('Папка переименована');
      } else {
        await api.renameFile(renameTarget.id, renameValue.trim());
        toast.success('Файл переименован');
      }
      setRenameTarget(null); setRenameValue('');
      load();
    } catch (e) { toast.error(e.message || 'Не удалось переименовать'); }
  };

  const openPicker = (mode, target) => {
    setPickerTarget(target);
    setPickerMode(mode);
  };

  const handlePicked = async (destFolderId /* string | null */) => {
    if (!pickerTarget || !pickerMode) return;
    const id = pickerTarget.id;
    const isFolder = pickerTarget._kind === 'folder';
    const name = isFolder ? pickerTarget.name : pickerTarget.filename;
    try {
      if (pickerMode === 'move') {
        if (isFolder) await api.moveFolder(id, destFolderId);
        else await api.moveFile(id, destFolderId);
        toast.success(`«${name}» перемещён`);
      } else {
        if (isFolder) await api.copyFolder(id, destFolderId);
        else await api.copyFile(id, destFolderId);
        toast.success(`«${name}» скопирован`);
      }
      setPickerMode(null); setPickerTarget(null);
      load();
    } catch (e) {
      toast.error(e.message || 'Операция не выполнена');
    }
  };

  const handleCreateFolder = async () => {
    if (!folderName.trim()) return;
    try {
      await api.createFolder(folderName.trim(), targetFolderId || null);
      toast.success(`Папка «${folderName}» создана`);
      setFolderName(''); setShowCreate(false);
      load();
    } catch (e) { toast.error(e.message); }
  };

  // ----- breadcrumb chain (walk up parentFolderId via /meta) -----
  // The server only returns the current folder, not its ancestors, so we
  // chase the parent chain ourselves with a sequence of /meta calls. Cached
  // by leaf folderId — re-walks only when the URL folder changes.
  const [chain, setChain] = useState([]); // [{id, name}, ... oldest -> newest]
  useEffect(() => {
    let cancelled = false;
    if (isRoot || isTrashView || !content?.currentFolder?.id) {
      setChain([]);
      return () => { cancelled = true; };
    }
    (async () => {
      const acc = [];
      let cur = content.currentFolder;
      // Push the leaf first; we'll reverse at the end.
      while (cur && cur.parentFolderId) {
        acc.push({ id: cur.id, name: cur.name });
        try {
          cur = await api.getFolderMeta(cur.parentFolderId);
          // FolderMeta uses {folderId, name, parentId, ...} — normalize.
          if (cur && cur.parentId !== undefined) {
            cur = { id: cur.folderId, name: cur.name, parentFolderId: cur.parentId };
          }
        } catch { cur = null; }
      }
      if (!cancelled) setChain(acc.reverse());
    })();
    return () => { cancelled = true; };
  }, [isRoot, isTrashView, content?.currentFolder?.id, content?.currentFolder?.parentFolderId]);

  // ----- render -----
  const leafName =
    displayFolderName(chain[chain.length - 1]?.name || content.currentFolder?.name || '') || '...';
  const breadcrumbItems = isTrashView
    ? [{ label: 'Корзина' }]
    : inTrashTree
      ? [{ label: 'Корзина', to: '/trash' }, ...chain.slice(0, -1).map((f) => ({ label: displayFolderName(f.name), to: `/folder/${f.id}` })), { label: leafName }]
      : isRoot
        ? [{ label: 'Мои файлы' }]
        : [
            { label: 'Мои файлы', to: '/' },
            ...chain.slice(0, -1).map((f) => ({ label: displayFolderName(f.name), to: `/folder/${f.id}` })),
            { label: leafName },
          ];

  return (
    <div className={`page ${dragOver ? 'is-drag' : ''}`} ref={dropRef}>
      {/* Topbar */}
      <header className="page-head">
        <div className="page-head-left">
          <Breadcrumbs items={breadcrumbItems} />
          <h1 className="page-title">
            {isTrash
              ? 'Корзина'
              : isRoot
                ? 'Мои файлы'
                : (displayFolderName(content.currentFolder?.name) || 'Мои файлы')}
          </h1>
        </div>

        <div className="page-head-right">
          <div className="search">
            <Icon name="search" size={16} />
            <input placeholder="Поиск файлов и папок…" value={search} onChange={(e) => setSearch(e.target.value)} />
          </div>
          <button className="icon-btn" title="Обновить" onClick={load}><Icon name="refresh" /></button>
          <div className="layout-switch">
            <button className={layout === 'grid' ? 'on' : ''} onClick={() => setLayout('grid')} title="Плитка">▦</button>
            <button className={layout === 'list' ? 'on' : ''} onClick={() => setLayout('list')} title="Список">≡</button>
          </div>
        </div>
      </header>

      {/* Action bar */}
      {!isTrash && (
        <div className="action-bar">
          <button className="btn btn-primary" onClick={() => fileInput.current?.click()} title="Загрузить один или несколько файлов">
            <Icon name="upload" /> Загрузить файл
          </button>
          {/* FR#1 (folder upload) — second hidden input with webkitdirectory.
              Fires the tree-upload path. */}
          <button className="btn" onClick={() => folderInput.current?.click()} title="Загрузить папку целиком (с сохранением структуры)">
            <Icon name="folderPlus" /> Загрузить папку
          </button>
          <button className="btn" onClick={() => setShowCreate(true)}>
            <Icon name="folderPlus" /> Новая папка
          </button>
          {/* FR#19/NFT#8 — CSV export of the current folder. */}
          <button
            className="btn"
            title="Экспорт списка файлов текущей папки в CSV"
            onClick={async () => {
              try {
                const fid = isRoot ? null : (content?.currentFolder?.id || null);
                const name = (content?.currentFolder?.name || 'folder').replace(/[^A-Za-z0-9_.-]/g, '_');
                await api.exportFolderCsv(fid, `${name}.csv`);
                toast.success('CSV скачан');
              } catch (e) { toast.error(e.message || 'Не удалось экспортировать'); }
            }}
          >
            <Icon name="download" /> Экспорт CSV
          </button>

          {/* FR#23/NFT#24 — sort dropdown. Re-fetches via load(). */}
          <div className="sort-ctl">
            <label htmlFor="sortField">Сортировка:</label>
            <select
              id="sortField"
              value={sortField}
              onChange={(e) => setSortField(e.target.value)}
              title="Поле сортировки"
            >
              <option value="name">по имени</option>
              <option value="size">по размеру</option>
              <option value="date">по дате</option>
            </select>
            <button
              className="icon-btn"
              title={sortDir === 'asc' ? 'По возрастанию (нажмите для убывания)' : 'По убыванию (нажмите для возрастания)'}
              onClick={() => setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'))}
            >
              {sortDir === 'asc' ? '↑' : '↓'}
            </button>
          </div>

          <input ref={fileInput} type="file" multiple style={{ display: 'none' }}
            onChange={(e) => e.target.files && startUpload(e.target.files)} />
          {/* webkitdirectory: browser sets file.webkitRelativePath = "folder/sub/file.ext" */}
          <input ref={folderInput} type="file" multiple webkitdirectory="" directory="" style={{ display: 'none' }}
            onChange={(e) => {
              if (!e.target.files?.length) return;
              const tree = Array.from(e.target.files).map((f) => ({
                file: f,
                relPath: f.webkitRelativePath || f.name,
              }));
              startUploadTree(tree);
              // reset so picking the same folder again still fires onChange
              e.target.value = '';
            }} />
          <div className="hint">Подсказка: перетащите файлы или папки в окно для загрузки</div>
        </div>
      )}

      {/* Drop overlay */}
      {dragOver && (
        <div className="drop-overlay">
          <Icon name="upload" size={48} />
          <div>Отпустите, чтобы загрузить</div>
        </div>
      )}

      {/* Content */}
      <section className="content">
        {loading ? (
          <SkeletonGrid />
        ) : (visibleFolders.length === 0 && visibleFiles.length === 0) ? (
          <Empty isTrash={isTrash} />
        ) : layout === 'grid' ? (
          <div className="grid">
            {visibleFolders.map((f) => (
              <Card key={`f-${f.id}`}
                kind="folder"
                item={{ ...f, _kind: 'folder' }}
                selected={selected === `f-${f.id}`}
                onSelect={() => setSelected(`f-${f.id}`)}
                onOpen={() => handleOpen({ ...f, _kind: 'folder' })}
                onContext={(e) => { e.preventDefault(); setMenu({ x: e.clientX, y: e.clientY, target: { ...f, _kind: 'folder' } }); }}
              />
            ))}
            {visibleFiles.map((f) => (
              <Card key={`x-${f.id}`}
                kind="file"
                item={{ ...f, _kind: 'file' }}
                tags={tagsByFile[f.id]}
                selected={selected === `x-${f.id}`}
                onSelect={() => setSelected(`x-${f.id}`)}
                onOpen={() => handleOpen({ ...f, _kind: 'file' })}
                onContext={(e) => { e.preventDefault(); setMenu({ x: e.clientX, y: e.clientY, target: { ...f, _kind: 'file' } }); }}
              />
            ))}
          </div>
        ) : (
          <Table
            folders={visibleFolders}
            files={visibleFiles}
            tagsByFile={tagsByFile}
            onOpen={handleOpen}
            onContext={(e, target) => { e.preventDefault(); setMenu({ x: e.clientX, y: e.clientY, target }); }}
          />
        )}
      </section>

      {/* Context menu */}
      {menu && (
        <ul ref={menuRef} className="ctx-menu" style={{ top: menu.y, left: menu.x }} onClick={(e) => e.stopPropagation()}>
          {!isTrash && menu.target._kind === 'file' && (
            <li onClick={() => { handleDownload(menu.target); setMenu(null); }}><Icon name="download" /> Скачать</li>
          )}
          {/* FR#16 — open preview/details modal. Available for any file
              type; modal itself decides image / text / placeholder. */}
          {!isTrash && menu.target._kind === 'file' && (
            <li onClick={() => { setPreviewFile(menu.target); setMenu(null); }}>
              <Icon name="info" /> Открыть (превью · теги · комментарии)
            </li>
          )}
          {/* FR#1 — folder zip download. Backend builds tmp .zip and streams it. */}
          {!isTrash && menu.target._kind === 'folder' && (
            <li onClick={() => {
              const t = menu.target;
              api.triggerDownloadFolder(t.id, t.name)
                .then(() => toast.success(`Скачано: ${t.name}.zip`))
                .catch((e) => toast.error(e.message || 'Не удалось скачать папку'));
              setMenu(null);
            }}>
              <Icon name="download" /> Скачать как ZIP
            </li>
          )}
          {!isTrash && (
            <li onClick={() => {
              setRenameTarget(menu.target);
              setRenameValue(menu.target._kind === 'folder' ? menu.target.name : menu.target.filename);
              setMenu(null);
            }}>
              <Icon name="rename" /> Переименовать
            </li>
          )}
          {!isTrash && (
            <li onClick={() => { openPicker('move', menu.target); setMenu(null); }}>
              <Icon name="move" /> Переместить
            </li>
          )}
          {!isTrash && (
            <li onClick={() => { openPicker('copy', menu.target); setMenu(null); }}>
              <Icon name="copy" /> Копировать
            </li>
          )}
          {isTrash && (
            <li onClick={() => { handleRestore(menu.target); setMenu(null); }}>
              <Icon name="restore" /> Восстановить
            </li>
          )}
          {!isTrash ? (
            <li className="danger" onClick={() => { handleSoftDelete(menu.target); setMenu(null); }}>
              <Icon name="trash" /> В корзину
            </li>
          ) : (
            <li className="danger" onClick={() => { handlePurge(menu.target); setMenu(null); }}>
              <Icon name="trash" /> Удалить навсегда
            </li>
          )}
        </ul>
      )}

      {/* FR#14/16/20/22 — universal preview modal (image / text / placeholder)
          with embedded tags · comments · audit pane on the right. The
          onTagsChanged callback re-fetches that one file's tag list so the
          inline mini-chip row on the card/row updates without a page
          reload. */}
      {previewFile && (
        <PreviewModal
          file={previewFile}
          onClose={() => setPreviewFile(null)}
          onTagsChanged={async (fileId) => {
            try {
              const fresh = await api.tagsForFile(fileId);
              setTagsByFile((m) => ({ ...m, [fileId]: fresh || [] }));
            } catch { /* swallow */ }
          }}
        />
      )}

      {/* Upload tray */}
      {uploads.length > 0 && (
        <div className="upload-tray">
          <div className="upload-tray-head">Загрузки <span>{uploads.length}</span></div>
          <div className="upload-tray-list">
            {uploads.map((u) => (
              <div key={u.id} className={`upl ${u.status}`}>
                <div className="upl-row">
                  <Icon name={u.status === 'error' ? 'close' : 'upload'} />
                  <div className="upl-name">{u.name}</div>
                  <div className="upl-pct">{Math.round((u.progress || 0) * 100)}%</div>
                  {/* Cancel button — only while uploading. abortRef.abort()
                      triggers xhr.onabort → promise rejects with aborted=true. */}
                  {u.status === 'uploading' && (
                    <button
                      className="upl-cancel"
                      title="Отменить загрузку"
                      onClick={() => cancelUpload(u.id)}
                    >×</button>
                  )}
                </div>
                <div className="upl-bar"><div className="upl-fill" style={{ width: `${(u.progress || 0) * 100}%` }} /></div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Modals */}
      <Modal
        open={showCreate}
        title="Новая папка"
        onClose={() => setShowCreate(false)}
        footer={
          <>
            <button className="btn" onClick={() => setShowCreate(false)}>Отмена</button>
            <button className="btn btn-primary" onClick={handleCreateFolder} disabled={!folderName.trim()}>Создать</button>
          </>
        }
      >
        <label className="field">
          <span>Имя папки</span>
          <input autoFocus value={folderName} onChange={(e) => setFolderName(e.target.value)}
                 onKeyDown={(e) => e.key === 'Enter' && handleCreateFolder()} placeholder="Например: Документы" />
        </label>
      </Modal>

      <Modal
        open={!!renameTarget}
        title={renameTarget?._kind === 'folder' ? 'Переименовать папку' : 'Переименовать файл'}
        onClose={() => setRenameTarget(null)}
        footer={
          <>
            <button className="btn" onClick={() => setRenameTarget(null)}>Отмена</button>
            <button className="btn btn-primary" onClick={handleRename} disabled={!renameValue.trim()}>Сохранить</button>
          </>
        }
      >
        <label className="field">
          <span>Новое имя</span>
          <input autoFocus value={renameValue} onChange={(e) => setRenameValue(e.target.value)}
                 onKeyDown={(e) => e.key === 'Enter' && handleRename()} />
        </label>
      </Modal>

      <FolderPickerModal
        open={!!pickerMode}
        mode={pickerMode}
        target={pickerTarget}
        onClose={() => { setPickerMode(null); setPickerTarget(null); }}
        onPick={handlePicked}
      />
    </div>
  );
}

// ----- Folder picker -----
function FolderPickerModal({ open, mode, target, onClose, onPick }) {
  const targetIsFolder = target?._kind === 'folder';
  const targetName = targetIsFolder ? target?.name : target?.filename;
  const targetKindLabel = targetIsFolder ? 'Папка' : 'Файл';
  const [stack, setStack] = useState([]); // array of {id|null, name}
  const [content, setContent] = useState({ subFolders: [], currentFolder: null });
  const [loading, setLoading] = useState(false);

  // Always use the *actual* folder id from the loaded content (server returns
  // the real root folder UUID even at depth 0). The stack is just for the
  // breadcrumb / navigation history.
  const here = {
    id: content?.currentFolder?.id ?? null,
    name: stack.length === 0
      ? 'Мои файлы'
      : (displayFolderName(content?.currentFolder?.name)
          || displayFolderName(stack[stack.length - 1]?.name)
          || '...'),
  };

  const load = useCallback(async (id) => {
    setLoading(true);
    try {
      const data = id == null ? await api.getRootContent() : await api.getFolderContent(id);
      setContent(data || { subFolders: [], currentFolder: null });
    } catch {
      setContent({ subFolders: [], currentFolder: null });
    } finally { setLoading(false); }
  }, []);

  useEffect(() => {
    if (!open) return;
    setStack([]);
    load(null);
  }, [open, load]);

  const enter = (folder) => {
    setStack((s) => [...s, { id: folder.id, name: folder.name }]);
    load(folder.id);
  };
  const goUp = () => {
    setStack((s) => {
      const next = s.slice(0, -1);
      const tgt = next[next.length - 1];
      load(tgt ? tgt.id : null);
      return next;
    });
  };

  // Hide the source folder itself from the destination list — you can't move
  // / copy a folder into itself, and showing it just invites confusion.
  const sub = (content.subFolders || []).filter((f) => !f.deletedAt && f.id !== target?.id);

  return (
    <Modal
      open={open}
      width={520}
      title={mode === 'move' ? `Переместить ${targetIsFolder ? 'папку' : 'файл'}` : `Копировать ${targetIsFolder ? 'папку' : 'файл'}`}
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>Отмена</button>
          <button className="btn btn-primary" disabled={!here.id || here.id === target?.id} onClick={() => onPick(here.id)}>
            {mode === 'move' ? 'Переместить сюда' : 'Скопировать сюда'}
          </button>
        </>
      }
    >
      {targetName && (
        <p className="muted small" style={{ marginBottom: 10 }}>
          {targetKindLabel}: <strong>{targetName}</strong>
        </p>
      )}
      <div className="picker-path">
        {stack.length > 0 && (
          <button className="icon-btn" onClick={goUp} title="Назад"><Icon name="back" /></button>
        )}
        <span className="picker-here">
          <Icon name="folder" size={14} /> {here.name}
        </span>
      </div>
      <div className="picker-list">
        {loading ? (
          <div className="muted small">Загрузка…</div>
        ) : sub.length === 0 ? (
          <div className="muted small">Подпапок нет — выберите текущую папку.</div>
        ) : (
          sub.map((f) => (
            <div key={f.id} className="picker-row" onDoubleClick={() => enter(f)}>
              <button className="picker-item" onClick={() => enter(f)}>
                <Icon name="folder" size={16} /> <span>{f.name}</span>
              </button>
            </div>
          ))
        )}
      </div>
    </Modal>
  );
}

function Card({ kind, item, tags, selected, onSelect, onOpen, onContext }) {
  const isFolder = kind === 'folder';
  return (
    <div
      className={`card ${selected ? 'selected' : ''} ${isFolder ? 'card-folder' : 'card-file'}`}
      onClick={onSelect}
      onDoubleClick={onOpen}
      onContextMenu={onContext}
    >
      <div className="card-thumb">
        {isFolder
          ? <div className="folder-thumb"><Icon name="folder" size={42} /></div>
          : <FileIcon contentType={item.contentType} filename={item.filename} size={36} />}
      </div>
      <div className="card-info">
        <div className="card-title" title={isFolder ? item.name : item.filename}>
          {isFolder ? item.name : item.filename}
        </div>
        <div className="card-sub">
          {isFolder ? 'Папка' : fmtSize(item.size)}
          <span className="dot">·</span>
          {fmtDate(isFolder ? item.createdAt : item.uploadedAt)}
        </div>
        {!isFolder && <TagMiniRow tags={tags} />}
      </div>
      <button className="card-more" onClick={(e) => {
        e.preventDefault();
        e.stopPropagation();
        onContext({ preventDefault: () => {}, clientX: e.clientX, clientY: e.clientY });
      }}>
        <Icon name="more" />
      </button>
    </div>
  );
}

function Table({ folders, files, tagsByFile, onOpen, onContext }) {
  return (
    <table className="ftable">
      <thead>
        <tr>
          <th>Имя</th>
          <th>Размер</th>
          <th>Тип</th>
          <th>Дата</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        {folders.map((f) => (
          <tr key={`tf-${f.id}`} onDoubleClick={() => onOpen({ ...f, _kind: 'folder' })} onContextMenu={(e) => onContext(e, { ...f, _kind: 'folder' })}>
            <td><div className="row-name"><span className="folder-mini"><Icon name="folder" /></span>{f.name}</div></td>
            <td>—</td>
            <td>Папка</td>
            <td>{fmtDate(f.createdAt)}</td>
            <td><button className="icon-btn" onClick={(e) => { e.preventDefault(); e.stopPropagation(); onContext(e, { ...f, _kind: 'folder' }); }}><Icon name="more" /></button></td>
          </tr>
        ))}
        {files.map((f) => {
          const tags = tagsByFile?.[f.id];
          return (
            <tr key={`tx-${f.id}`} onDoubleClick={() => onOpen({ ...f, _kind: 'file' })} onContextMenu={(e) => onContext(e, { ...f, _kind: 'file' })}>
              <td>
                <div className="row-name">
                  <FileIcon contentType={f.contentType} filename={f.filename} size={18} />
                  <span>{f.filename}</span>
                  {tags && tags.length > 0 && <TagMiniRow tags={tags} />}
                </div>
              </td>
              <td>{fmtSize(f.size)}</td>
              <td>{prettyType(f.contentType, f.filename)}</td>
              <td>{fmtDate(f.uploadedAt)}</td>
              <td><button className="icon-btn" onClick={(e) => { e.preventDefault(); e.stopPropagation(); onContext(e, { ...f, _kind: 'file' }); }}><Icon name="more" /></button></td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

function SkeletonGrid() {
  return (
    <div className="grid">
      {Array.from({ length: 8 }).map((_, i) => (
        <div key={i} className="card skeleton">
          <div className="sk-thumb" />
          <div className="sk-line" />
          <div className="sk-line short" />
        </div>
      ))}
    </div>
  );
}

function Empty({ isTrash }) {
  return (
    <div className="empty">
      <div className="empty-art">
        <Icon name={isTrash ? 'trash' : 'folder'} size={56} />
      </div>
      <h3>{isTrash ? 'Корзина пуста' : 'Здесь пока ничего нет'}</h3>
      <p>{isTrash
        ? 'Удалённые файлы и папки будут появляться здесь.'
        : 'Перетащите файлы в окно или нажмите «Загрузить файл», чтобы начать.'}</p>
    </div>
  );
}
