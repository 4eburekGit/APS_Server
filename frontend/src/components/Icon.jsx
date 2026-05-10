// Inline SVG icon set — small, hand-picked, no external deps.
const PATHS = {
  cloud:    'M19.35 10.04A7.49 7.49 0 0 0 12 4a7.5 7.5 0 0 0-6.94 4.66A6 6 0 0 0 6 20h13a5 5 0 0 0 .35-9.96z',
  home:     'M12 3l9 8h-3v9h-4v-6h-4v6H6v-9H3z',
  trash:    'M9 3l1-1h4l1 1h4v2H5V3h4zm-3 4h12l-1 13a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2L6 7z',
  logout:   'M16 17v-3H9v-4h7V7l5 5-5 5zM4 5h7V3H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h7v-2H4z',
  upload:   'M5 20h14v-2H5v2zm0-10h4v6h6v-6h4l-7-7-7 7z',
  folder:   'M10 4H4c-1.11 0-2 .89-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-8l-2-2z',
  folderPlus:'M20 6h-8l-2-2H4c-1.11 0-2 .89-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2zm-7 8h-2v2h-2v-2H7v-2h2v-2h2v2h2v2z',
  file:     'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8l-6-6zm4 18H6V4h7v5h5v11z',
  fileImg:  'M21 19V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z',
  fileVid:  'M17 10.5V7a1 1 0 0 0-1-1H4a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-3.5l4 4v-11l-4 4z',
  fileAud:  'M12 3v10.55A4 4 0 1 0 14 17V7h4V3h-6z',
  fileZip:  'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8l-6-6zm-3 17h-2v-2h2v2zm0-4h-2v-2h2v2zm0-4h-2V9h2v2z',
  fileDoc:  'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z',
  download: 'M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z',
  more:     'M12 8a2 2 0 1 0 0-4 2 2 0 0 0 0 4zm0 6a2 2 0 1 0 0-4 2 2 0 0 0 0 4zm0 6a2 2 0 1 0 0-4 2 2 0 0 0 0 4z',
  rename:   'M3 17.25V21h3.75L17.81 9.94l-3.75-3.75zM20.71 7.04a1 1 0 0 0 0-1.41l-2.34-2.34a1 1 0 0 0-1.41 0l-1.83 1.83 3.75 3.75z',
  copy:     'M16 1H4a2 2 0 0 0-2 2v14h2V3h12V1zm3 4H8a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2zm0 16H8V7h11v14z',
  move:     'M15.5 14L20 9.5 15.5 5v3H4v3h11.5v3z',
  restore:  'M13 3a9 9 0 0 0-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42A8.97 8.97 0 0 0 13 21a9 9 0 0 0 0-18z',
  search:   'M15.5 14h-.79l-.28-.27A6.5 6.5 0 1 0 13 15.5l.27.28v.79l5 5L19.5 20zm-6 0A4.5 4.5 0 1 1 14 9.5 4.5 4.5 0 0 1 9.5 14z',
  close:    'M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z',
  back:     'M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20z',
  refresh:  'M17.65 6.35A7.95 7.95 0 0 0 12 4a8 8 0 1 0 7.45 11h-2.09A6 6 0 0 1 6 12a6 6 0 0 1 10.24-4.24L13 11h7V4z',
  info:     'M12 2a10 10 0 1 0 10 10A10 10 0 0 0 12 2zm1 15h-2v-6h2zm0-8h-2V7h2z',
  // Tag: rounded label with hole
  tag:      'M21.41 11.58 12.41 2.58A2 2 0 0 0 11 2H4a2 2 0 0 0-2 2v7a2 2 0 0 0 .59 1.41l9 9a2 2 0 0 0 2.83 0l7-7a2 2 0 0 0 0-2.83zM6.5 8A1.5 1.5 0 1 1 6.5 5a1.5 1.5 0 0 1 0 3z',
  // Chart / pie segment for Stats
  chart:    'M11 2v9h9a9 9 0 1 1-9-9zm2 0a9 9 0 0 1 9 9h-9z',
  // Shield with check for Profile / 2FA
  shield:   'M12 2 4 5v6c0 5 3.5 9.5 8 11 4.5-1.5 8-6 8-11V5l-8-3zm-1 14-4-4 1.4-1.4L11 13.2l4.6-4.6L17 10z',
};

export default function Icon({ name, size = 18, className = '' }) {
  const d = PATHS[name];
  if (!d) return null;
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" className={`icon ${className}`} fill="currentColor" aria-hidden="true">
      <path d={d} />
    </svg>
  );
}
