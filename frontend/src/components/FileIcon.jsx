// FileIcon — a small per-extension icon. We draw the canonical "page with
// a folded corner" shape ourselves, then stamp a colored label badge near
// the bottom (PDF, DOC, JSON, …). Extensions we don't have a label for
// fall back to a tinted generic icon by mime category.

const EXT = {
  // Documents
  pdf:  { label: 'PDF',  color: '#e0413a' },
  doc:  { label: 'DOC',  color: '#2a5dbf' },
  docx: { label: 'DOC',  color: '#2a5dbf' },
  rtf:  { label: 'RTF',  color: '#2a5dbf' },
  odt:  { label: 'ODT',  color: '#2a5dbf' },
  // Spreadsheets
  xls:  { label: 'XLS',  color: '#1f8a4c' },
  xlsx: { label: 'XLS',  color: '#1f8a4c' },
  csv:  { label: 'CSV',  color: '#1f8a4c' },
  ods:  { label: 'ODS',  color: '#1f8a4c' },
  // Presentations
  ppt:  { label: 'PPT',  color: '#d35400' },
  pptx: { label: 'PPT',  color: '#d35400' },
  odp:  { label: 'ODP',  color: '#d35400' },
  // Plain text / markup
  txt:  { label: 'TXT',  color: '#5d6b7a' },
  md:   { label: 'MD',   color: '#5d6b7a' },
  log:  { label: 'LOG',  color: '#5d6b7a' },
  // Code / data
  json: { label: 'JSON', color: '#b8860b' },
  xml:  { label: 'XML',  color: '#b8860b' },
  yaml: { label: 'YML',  color: '#b8860b' },
  yml:  { label: 'YML',  color: '#b8860b' },
  toml: { label: 'TOML', color: '#b8860b' },
  ini:  { label: 'INI',  color: '#b8860b' },
  conf: { label: 'CFG',  color: '#b8860b' },
  cfg:  { label: 'CFG',  color: '#b8860b' },
  env:  { label: 'ENV',  color: '#b8860b' },
  html: { label: 'HTML', color: '#e67e22' },
  htm:  { label: 'HTML', color: '#e67e22' },
  css:  { label: 'CSS',  color: '#3498db' },
  scss: { label: 'SCSS', color: '#cf649a' },
  js:   { label: 'JS',   color: '#f0c000' },
  mjs:  { label: 'JS',   color: '#f0c000' },
  cjs:  { label: 'JS',   color: '#f0c000' },
  ts:   { label: 'TS',   color: '#2a78d6' },
  jsx:  { label: 'JSX',  color: '#61dafb' },
  tsx:  { label: 'TSX',  color: '#61dafb' },
  java: { label: 'JAVA', color: '#cc7a18' },
  kt:   { label: 'KT',   color: '#7f52ff' },
  py:   { label: 'PY',   color: '#3776ab' },
  rb:   { label: 'RB',   color: '#cc342d' },
  php:  { label: 'PHP',  color: '#7377ad' },
  go:   { label: 'GO',   color: '#00add8' },
  rs:   { label: 'RS',   color: '#dea584' },
  c:    { label: 'C',    color: '#3973a6' },
  h:    { label: 'H',    color: '#3973a6' },
  cpp:  { label: 'C++',  color: '#3973a6' },
  cc:   { label: 'C++',  color: '#3973a6' },
  cs:   { label: 'C#',   color: '#68217a' },
  swift:{ label: 'SWFT', color: '#f05138' },
  sh:   { label: 'SH',   color: '#4d8a4d' },
  bat:  { label: 'BAT',  color: '#4d8a4d' },
  ps1:  { label: 'PS1',  color: '#4d8a4d' },
  sql:  { label: 'SQL',  color: '#005c84' },
  db:   { label: 'DB',   color: '#005c84' },
  sqlite:{label: 'DB',   color: '#005c84' },
  // Archives
  zip:  { label: 'ZIP',  color: '#7d6f44' },
  rar:  { label: 'RAR',  color: '#7d6f44' },
  '7z': { label: '7Z',   color: '#7d6f44' },
  tar:  { label: 'TAR',  color: '#7d6f44' },
  gz:   { label: 'GZ',   color: '#7d6f44' },
  bz2:  { label: 'BZ2',  color: '#7d6f44' },
  xz:   { label: 'XZ',   color: '#7d6f44' },
  // Disks / packages
  iso:  { label: 'ISO',  color: '#555' },
  dmg:  { label: 'DMG',  color: '#555' },
  exe:  { label: 'EXE',  color: '#555' },
  msi:  { label: 'MSI',  color: '#555' },
  apk:  { label: 'APK',  color: '#a4c639' },
  deb:  { label: 'DEB',  color: '#a8243d' },
  rpm:  { label: 'RPM',  color: '#a8243d' },
  // Fonts
  ttf:  { label: 'TTF',  color: '#7c3aed' },
  otf:  { label: 'OTF',  color: '#7c3aed' },
  woff: { label: 'WOFF', color: '#7c3aed' },
  woff2:{ label: 'WOFF', color: '#7c3aed' },
};

// MIME-category fallback when no extension match (e.g. uploads from
// browsers that don't add an extension). These render only a tint, no
// label, so they look like the previous generic icons.
function categoryFor(ct, ext) {
  if (ct?.startsWith('image/'))  return { color: '#1aa18b', kind: 'image' };
  if (ct?.startsWith('video/'))  return { color: '#9b59b6', kind: 'video' };
  if (ct?.startsWith('audio/'))  return { color: '#e74c3c', kind: 'audio' };
  if (/^image\b/i.test(ext) || /(png|jpe?g|gif|webp|svg|bmp|ico|heic|tiff?)$/i.test(ext)) return { color: '#1aa18b', kind: 'image' };
  if (/(mp4|mkv|mov|webm|avi|wmv|m4v)$/i.test(ext)) return { color: '#9b59b6', kind: 'video' };
  if (/(mp3|wav|flac|ogg|m4a|aac|opus)$/i.test(ext)) return { color: '#e74c3c', kind: 'audio' };
  return { color: '#94a3b8', kind: 'doc' };
}

// Inner-content glyph for category fallback. Drawn inside the page area.
function CategoryGlyph({ kind }) {
  if (kind === 'image') return (
    <>
      <circle cx="12" cy="14" r="1.6" fill="currentColor" opacity="0.9" />
      <path d="M7 21l3.5-4 2.5 3 3-3.5L20 21z" fill="currentColor" opacity="0.85" />
    </>
  );
  if (kind === 'video') return (
    <path d="M10 14l5 3-5 3v-6z" fill="currentColor" />
  );
  if (kind === 'audio') return (
    <path d="M12 13v6.2a2.4 2.4 0 1 1-1.4-2.2V13h4v-2h-2.6z" fill="currentColor" />
  );
  return null;
}

export default function FileIcon({ contentType = '', filename = '', size = 22 }) {
  const ext = (filename.split('.').pop() || '').toLowerCase();
  const known = EXT[ext];
  const cat = known ? null : categoryFor(contentType, ext);
  const color = known ? known.color : cat.color;
  const label = known ? known.label : null;

  // Auto-shrink badge text for longer labels so 4-character ones still fit.
  const labelFontSize = label
    ? (label.length >= 4 ? 4.6 : label.length === 3 ? 5.4 : 6.2)
    : 0;

  return (
    <span className="file-icon" aria-hidden="true">
      <svg width={size} height={size} viewBox="0 0 32 32" className="icon">
        {/* Page body */}
        <path
          d="M8 3h12l6 6v18a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z"
          fill="#fff"
          stroke="#cbd3dc"
          strokeWidth="1.2"
        />
        {/* Folded corner */}
        <path d="M20 3v6h6z" fill="#e7ecf2" stroke="#cbd3dc" strokeWidth="1.2" strokeLinejoin="round" />

        {/* Category glyph for non-labeled files */}
        {cat && (
          <g style={{ color }}>
            <CategoryGlyph kind={cat.kind} />
          </g>
        )}

        {/* Colored label badge for known extensions */}
        {label && (
          <>
            <rect x="4" y="18" width="20" height="8" rx="1.6" fill={color} />
            <text
              x="14"
              y={22 + labelFontSize / 3.2}
              textAnchor="middle"
              fontFamily="-apple-system, Segoe UI, Roboto, sans-serif"
              fontSize={labelFontSize}
              fontWeight="700"
              fill="#fff"
              letterSpacing="0.2"
            >
              {label}
            </text>
          </>
        )}
      </svg>
    </span>
  );
}
