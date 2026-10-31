(function () {
    'use strict';
    if (window !== window.top || window.NativeYouTubePlayback || !window.NativePlayback) return;
    window.NativeYouTubePlayback = true;
    var lastAutomaticKey = null;
    var panel = null;
    var currentFrame = null;
    var currentVideo = null;

    function videoFromFrame(frame) {
        try {
            var url = new URL(frame.src, location.href);
            if (url.protocol !== 'https:' || url.username || url.password ||
                (url.port && url.port !== '443') ||
                !['youtube.com', 'www.youtube.com', 'youtube-nocookie.com',
                    'www.youtube-nocookie.com'].includes(url.hostname)) return null;
            var match = /^\/embed\/([A-Za-z0-9_-]{11})$/.exec(url.pathname);
            return match ? match[1] : null;
        } catch (_) { return null; }
    }

    function pauseFrame(frame) {
        if (!frame || !frame.contentWindow) return;
        var origin = new URL(frame.src, location.href).origin;
        frame.contentWindow.postMessage(JSON.stringify({
            event: 'command', func: 'pauseVideo', args: []
        }), origin);
    }

    function launch() {
        if (!currentFrame || !currentVideo || document.visibilityState === 'hidden') return;
        pauseFrame(currentFrame);
        window.NativePlayback.postMessage(JSON.stringify({
            type: 'youtube', videoId: currentVideo
        }));
    }

    function removePanel() {
        if (panel) panel.remove();
        panel = null;
        currentFrame = null;
        currentVideo = null;
    }

    function scan() {
        if (location.pathname !== '/watch') {
            removePanel();
            lastAutomaticKey = null;
            return;
        }
        var frame = Array.from(document.querySelectorAll('iframe[src]')).find(function (candidate) {
            return videoFromFrame(candidate) !== null;
        });
        if (!frame) {
            removePanel();
            return;
        }
        currentFrame = frame;
        currentVideo = videoFromFrame(frame);
        if (!panel) {
            panel = document.createElement('div');
            panel.setAttribute('data-native-youtube-handoff', '');
            panel.style.cssText = 'position:fixed;right:24px;bottom:24px;z-index:2147483647;' +
                'padding:16px;background:#141414;color:white;border:1px solid #666;border-radius:8px';
            var text = document.createElement('div');
            text.textContent = 'This video plays in the YouTube app. Return to Insiflix with Back.';
            var button = document.createElement('button');
            button.type = 'button';
            button.textContent = 'Open in YouTube';
            button.style.cssText = 'margin-top:12px;padding:12px 20px;font-size:18px;' +
                'background:#b20710;color:white;border:2px solid white;border-radius:6px';
            button.addEventListener('click', launch);
            panel.appendChild(text);
            panel.appendChild(button);
            document.body.appendChild(panel);
        }
        var key = location.pathname + location.search + '|' + currentVideo;
        if (key !== lastAutomaticKey && document.visibilityState !== 'hidden') {
            lastAutomaticKey = key;
            launch();
        }
    }

    new MutationObserver(scan).observe(document, {
        childList: true, subtree: true, attributes: true, attributeFilter: ['src']
    });
    document.addEventListener('visibilitychange', scan);
    window.addEventListener('popstate', scan);
    window.addEventListener('native-playback-pause', function () { pauseFrame(currentFrame); });
    scan();
})();
