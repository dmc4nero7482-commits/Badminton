const CACHE = 'badminton-v2';
const STATIC = ['./manifest.json', './icon.svg'];

self.addEventListener('install', e => {
    e.waitUntil(
        caches.open(CACHE).then(c => c.addAll([...STATIC, './badminton.html']))
    );
    self.skipWaiting();
});

self.addEventListener('activate', e => {
    e.waitUntil(
        caches.keys().then(keys =>
            Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))
        )
    );
    self.clients.claim();
});

self.addEventListener('fetch', e => {
    const url = new URL(e.request.url);

    // HTML：網路優先，網路失敗才用快取（確保每次重新整理都拿最新版）
    if (url.pathname.endsWith('.html') || url.pathname.endsWith('/')) {
        e.respondWith(
            fetch(e.request)
                .then(res => {
                    caches.open(CACHE).then(c => c.put(e.request, res.clone()));
                    return res;
                })
                .catch(() => caches.match(e.request))
        );
        return;
    }

    // 其他靜態檔案：快取優先
    e.respondWith(
        caches.match(e.request).then(cached => cached || fetch(e.request))
    );
});
