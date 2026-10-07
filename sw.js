self.addEventListener('install', (event) => {
    self.skipWaiting();
});

self.addEventListener('activate', (event) => {
    event.waitUntil(clients.claim());
});

// Menangkap kiriman file gambar dari GoPay / Menu Share Android
self.addEventListener('fetch', (event) => {
    if (event.request.method === 'POST' && event.request.url.includes('/cetak-struk/')) {
        event.respondWith((async () => {
            const formData = await event.request.formData();
            const file = formData.get('gambar_struk');
            
            // Simpan gambar yang dilempar GoPay ke dalam memori Cache Browser
            if (file) {
                const cache = await caches.open('struk-cache');
                await cache.put('/gambar-terakhir', new Response(file));
            }
            
            // Arahkan web untuk membuka halaman utama dengan mode cetak
            return Response.redirect('/cetak-struk/?mode=cetak', 303);
        })());
    }
});
