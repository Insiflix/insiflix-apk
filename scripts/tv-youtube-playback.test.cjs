const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { runInNewContext } = require('node:vm');
const script = readFileSync(resolve(__dirname, '../app/src/main/assets/tv-youtube-playback.js'), 'utf8');

function setup({ src = 'https://www.youtube.com/embed/C252xkuyiIY?enablejsapi=1',
    hidden = false, path = '/watch', embedded = false } = {}) {
    const messages = [], commands = [], elements = [], listeners = {}, windowListeners = {};
    let observe;
    const frame = { src, contentWindow: { postMessage: (data, origin) =>
        commands.push({ data: JSON.parse(data), origin }) } };
    const document = {
        visibilityState: hidden ? 'hidden' : 'visible',
        body: { appendChild(element) { elements.push(element); } },
        querySelectorAll() { return frame.src ? [frame] : []; },
        createElement(tag) {
            return { tag, style: {}, children: [], listeners: {},
                setAttribute() {},
                appendChild(element) { this.children.push(element); },
                addEventListener(event, callback) { this.listeners[event] = callback; },
                remove() { elements.splice(elements.indexOf(this), 1); } };
        },
        addEventListener(event, callback) { listeners[event] = callback; }
    };
    const window = { NativePlayback: { postMessage: data => messages.push(JSON.parse(data)) },
        addEventListener(event, callback) { windowListeners[event] = callback; } };
    window.top = embedded ? {} : window;
    const location = { pathname: path, search: '?id=C252xkuyiIY', href: 'https://insiflix.com/watch?id=C252xkuyiIY' };
    class MutationObserver {
        constructor(callback) { observe = callback; }
        observe() {}
    }
    const context = { window, document, location, MutationObserver, URL };
    runInNewContext(script, context);
    return { window, document, location, frame, messages, commands, elements, listeners,
        windowListeners, scan: () => observe && observe(), context };
}

test('YouTube embed opens its ID once and pauses the embedded player', () => {
    const env = setup();
    assert.deepEqual(env.messages, [{ type: 'youtube', videoId: 'C252xkuyiIY' }]);
    assert.equal(env.commands[0].data.func, 'pauseVideo');
    assert.equal(env.commands[0].origin, 'https://www.youtube.com');
    env.scan();
    env.listeners.visibilitychange();
    assert.equal(env.messages.length, 1, 'returning must not reopen YouTube');
    env.elements[0].children[1].listeners.click();
    assert.equal(env.messages.length, 2, 'the replay button must open YouTube again');
});

test('only exact HTTPS YouTube embeds on watch pages are handed off', () => {
    for (const src of [
        'https://evil.example/embed/C252xkuyiIY',
        'https://www.youtube.com.evil.example/embed/C252xkuyiIY',
        'http://www.youtube.com/embed/C252xkuyiIY',
        'https://user:pass@www.youtube.com/embed/C252xkuyiIY',
        'https://www.youtube.com:444/embed/C252xkuyiIY',
        'https://www.youtube.com/embed/not-valid',
        'https://www.youtube.com/watch?v=C252xkuyiIY',
        'https://api.insiflix.com/videos/watch/example'
    ]) {
        assert.equal(setup({ src }).messages.length, 0, src);
    }
    assert.equal(setup({ path: '/lounge' }).messages.length, 0);
    assert.equal(setup({ embedded: true }).messages.length, 0);
    assert.equal(setup({ src: 'https://www.youtube-nocookie.com/embed/C252xkuyiIY' }).messages.length, 1);
});

test('SPA changes open the next YouTube video without duplicate launches', () => {
    const env = setup();
    env.frame.src = '';
    env.scan();
    assert.equal(env.elements.length, 0);
    env.frame.src = 'https://www.youtube.com/embed/C252xkuyiIY';
    env.scan();
    assert.equal(env.messages.length, 1, 'React remounts should not reopen the same video');
    env.frame.src = 'https://www.youtube.com/embed/abcdefghijk';
    env.scan();
    assert.equal(env.messages[1].videoId, 'abcdefghijk');
    env.location.pathname = '/';
    env.scan();
    assert.equal(env.elements.length, 0);
    env.location.pathname = '/watch';
    env.scan();
    assert.equal(env.messages.length, 3, 'selecting the video again after leaving should launch it');
});

test('background pages defer handoff and backgrounding pauses YouTube', () => {
    const env = setup({ hidden: true });
    assert.equal(env.messages.length, 0);
    env.document.visibilityState = 'visible';
    env.listeners.visibilitychange();
    assert.equal(env.messages.length, 1);
    env.windowListeners['native-playback-pause']();
    assert.equal(env.commands.at(-1).data.func, 'pauseVideo');
    runInNewContext(script, env.context);
    assert.equal(env.messages.length, 1, 'repeated script injection is idempotent');
});
