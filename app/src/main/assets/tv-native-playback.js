(function () {
    'use strict';
    if (window !== window.top || window.NativeTVPlayback || !window.NativePlayback) return;
    var active = null;
    var sequence = 0;
    var originalPlay = HTMLMediaElement.prototype.play;
    function streamUrl(video) {
        try {
            var source = video.currentSrc || video.src ||
                (video.querySelector('source[src]') || {}).src;
            if (!source) return null;
            var url = new URL(source, location.href);
            return url.protocol === 'https:' && !url.username && !url.password &&
                (!url.port || url.port === '443') &&
                ((url.hostname === 'api.insiflix.com' && url.pathname.startsWith('/videos/watch/')) ||
                    url.origin === location.origin) ? url.href : null;
        } catch (_) { return null; }
    }
    function open(video) {
        if (!(video instanceof HTMLVideoElement)) return false;
        if (active) return active.video === video;
        var url = streamUrl(video);
        if (!url) return false;
        var position = Number.isFinite(video.currentTime) ? video.currentTime : 0;
        var id = String(Date.now()) + '-' + String(++sequence);
        var sources = Array.from(video.querySelectorAll('source[src]')).map(function (source) {
            return { element: source, src: source.getAttribute('src') };
        });
        var request = {
            id: id, url: url, position: position, title: document.title,
            subtitles: Array.from(video.querySelectorAll('track[src]')).filter(function (track) {
                return track.kind === 'subtitles' || track.kind === 'captions';
            }).map(function (track) {
                return { url: track.src, language: track.srclang, label: track.label, selected: track.default };
            })
        };
        active = { id: id, video: video, src: video.getAttribute('src'), sources: sources };
        video.pause();
        // Release WebView's decoder before the native player acquires the video hardware.
        video.autoplay = false;
        video.removeAttribute('src');
        sources.forEach(function (source) { source.element.removeAttribute('src'); });
        video.load();
        window.NativePlayback.postMessage(JSON.stringify(request));
        return true;
    }
    HTMLMediaElement.prototype.play = function () {
        if (open(this)) return Promise.resolve();
        return originalPlay.apply(this, arguments);
    };
    document.addEventListener('play', function (event) { open(event.target); }, true);
    window.NativeTVPlayback = {
        complete: function (id, position, ended) {
            if (!active || active.id !== id) return;
            var state = active;
            var video = state.video;
            // Keep the session guarded while restoring the source and position.
            if (state.src !== null) video.setAttribute('src', state.src);
            state.sources.forEach(function (source) { source.element.setAttribute('src', source.src); });
            video.addEventListener('loadedmetadata', function restore() {
                video.removeEventListener('loadedmetadata', restore);
                if (Number.isFinite(position) && position >= 0) {
                    video.currentTime = Number.isFinite(video.duration) ?
                        Math.min(position, video.duration) : position;
                }
                video.pause();
            }, { once: true });
            video.load();
            active = null;
            if (ended) video.dispatchEvent(new Event('ended'));
        }
    };
    document.querySelectorAll('video').forEach(function (video) {
        if (!video.paused) open(video);
    });
})();
