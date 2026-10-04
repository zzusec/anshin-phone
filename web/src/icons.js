const paths = {
 shield: '<path d="M12 3 20 6v6c0 5-4 8-8 10-4-2-8-5-8-10V6z"/><path d="m8 12 3 3 5-6"/>',
 clean: '<path d="m15 3-4 8M7 10l10 4-3 7-10-4zM5 15l9 4"/>',
 block: '<circle cx="12" cy="12" r="9"/><path d="m6 6 12 12"/>',
 family: '<circle cx="9" cy="8" r="3"/><path d="M3 21v-3a6 6 0 0 1 12 0v3M17 5a3 3 0 0 1 0 6M21 21v-3a5 5 0 0 0-3-4"/>',
 bell: '<path d="M18 8a6 6 0 0 0-12 0c0 8-3 8-3 10h18c0-2-3-2-3-10M10 22h4"/>',
 lock: '<rect x="4" y="10" width="16" height="12" rx="2"/><path d="M8 10V6a4 4 0 0 1 8 0v4M12 15v3"/>',
 history: '<path d="M3 10a9 9 0 1 1 2 8M3 3v7h7M12 7v6l4 2"/>',
 settings: '<path d="m10 2-.6 3-2.6 1.5L4 5.5l-2 3 2.3 2v3L2 15.5l2 3 2.8-1 2.6 1.5.6 3h4l.6-3 2.6-1.5 2.8 1 2-3-2.3-2v-3L22 8.5l-2-3-2.8 1L14.6 5 14 2z"/><circle cx="12" cy="12" r="3"/>',
 home: '<path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1z"/>',
 restore: '<path d="M3 10a9 9 0 1 1 2 8M3 3v7h7"/><path d="M12 7v6h4"/>',
 clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
 back: '<path d="m15 5-7 7 7 7"/>', close: '<path d="m6 6 12 12M6 18 18 6"/>',
 check: '<path d="m5 12 4 4L19 6"/>', arrow: '<path d="m9 5 7 7-7 7"/>',
 app: '<rect x="4" y="3" width="16" height="18" rx="3"/><path d="M8 7h8M8 11h5M8 17h8"/>',
 link: '<path d="m10 14 4-4M8 16l-2 2a4 4 0 0 1-6-6l5-5a4 4 0 0 1 6 0M16 8l2-2a4 4 0 0 1 6 6l-5 5a4 4 0 0 1-6 0"/>',
 info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7h.01"/>'
};
export function icon(name, size = 24) {
 return `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">${paths[name] || paths.app}</svg>`;
}
export function ring() {
 return `<svg class="ring" viewBox="0 0 300 300" fill="none" aria-hidden="true" focusable="false"><circle cx="150" cy="150" r="134" stroke="#E5ECF7" stroke-width="12"/><circle cx="150" cy="150" r="134" stroke="currentColor" stroke-width="12" stroke-linecap="round" stroke-dasharray="640 842" transform="rotate(-90 150 150)"/><circle cx="150" cy="150" r="115" stroke="#EEF2F8" stroke-width="1"/></svg>`;
}
