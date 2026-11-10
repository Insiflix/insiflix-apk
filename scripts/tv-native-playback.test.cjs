const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { runInNewContext } = require('node:vm');

const script = readFileSync(resolve(__dirname, '../app/src/main/assets/tv-native-playback.js'), 'utf8');
function setup() {
    const messages = [];
    class Media {
        constructor() {
            this.currentTime = 42;
            this.duration = 120;
            this.paused = true;
            this.src = 'https://api.insiflix.com/videos/watch/example?watchToken=test';
            this.autoplay = true;
            this.listeners = {};
            this.attributes = { src: this.src };
        }
        play() { this.webPlayCount = (this.webPlayCount || 0) + 1; return Promise.resolve(); }
        pause() { this.paused = true; }
        load() { this.loadCount = (this.loadCount || 0) + 1; }
        getAttribute(key) { return this.attributes[key] ?? null; }
        setAttribute(key, value) { this.attributes[key] = value; if (key === 'src') this.src = value; }
        removeAttribute(key) { delete this.attributes[key]; if (key === 'src') this.src = ''; }
        querySelector() { return null; }
        querySelectorAll() { return []; }
        addEventListener(name, fn) { this.listeners[name] = fn; }
        removeEventListener(name) { delete this.listeners[name]; }
        dispatchEvent(event) { this.lastEvent = event.type; }
    }
    class Video extends Media {}
    const window = { NativePlayback: { postMessage: value => messages.push(JSON.parse(value)) } };
    window.top = window;
    const context = {
        window, HTMLMediaElement: Media, HTMLVideoElement: Video,
        location: { href: 'https://insiflix.com/watch/example', origin: 'https://insiflix.com' },
        document: { title: 'Test video', addEventListener() {}, querySelectorAll() { return []; } },
        URL, Event, Promise, Number, Array
    };
    runInNewContext(script, context);
    return { Video, Media, window, messages, context };
}

test('direct video starts native playback and releases the web decoder', async () => {
    const { Video, messages } = setup();
    const video = new Video();
    await video.play();
    assert.equal(messages.length, 1);
    assert.equal(messages[0].position, 42);
    assert.equal(video.webPlayCount, undefined);
    assert.equal(video.getAttribute('src'), null);
    assert.equal(video.autoplay, false);
    assert.equal(video.loadCount, 1);
    await video.play();
    assert.equal(messages.length, 1, 'duplicate play must not launch a second native player');
});

test('return restores the source and position without restarting web playback', async () => {
    const { Video, window, messages } = setup();
    const video = new Video();
    const url = video.src;
    await video.play();
    window.NativeTVPlayback.complete(messages[0].id, 87.5, false);
    assert.equal(video.src, url);
    video.listeners.loadedmetadata();
    assert.equal(video.currentTime, 87.5);
    assert.equal(video.paused, true);
    await video.play();
    assert.equal(messages.length, 2, 'explicit play after returning opens native again');
});

test('unsupported native video resumes in web player without a handoff loop', async () => {
    const { Video, window, messages } = setup();
    const video = new Video();
    const url = video.src;
    await video.play();
    window.NativeTVPlayback.complete('stale-id', 10, false, true);
    assert.equal(video.src, '', 'stale replies cannot restore or bypass playback');
    window.NativeTVPlayback.complete(messages[0].id, 53, false, true);
    assert.equal(video.src, url);
    video.listeners.loadedmetadata();
    assert.equal(video.currentTime, 53);
    assert.equal(video.webPlayCount, 1, 'fallback resumes browser playback');
    await video.play();
    assert.equal(video.webPlayCount, 2);
    assert.equal(messages.length, 1, 'same unsupported source stays in the browser');
    video.setAttribute('src', url.replace('example?', 'another?'));
    await video.play();
    assert.equal(messages.length, 2, 'a new source can use native playback again');
});

test('foreign, insecure, credentialed and nonstandard-port URLs stay out of native playback', async () => {
    for (const url of [
        'https://untrusted.example/video.mp4',
        'http://api.insiflix.com/videos/watch/example',
        'https://api.insiflix.com:444/videos/watch/example',
        'https://user:password@api.insiflix.com/videos/watch/example',
        'https://api.insiflix.com/not-a-stream'
    ]) {
        const { Video, messages } = setup();
        const video = new Video();
        video.src = url;
        await video.play();
        assert.equal(messages.length, 0);
        assert.equal(video.webPlayCount, 1);
    }
});

test('audio and embedded frames are not intercepted', async () => {
    const { Media, messages, context } = setup();
    await new Media().play();
    assert.equal(messages.length, 0);
    const original = context.HTMLMediaElement.prototype.play;
    context.window.NativeTVPlayback = undefined;
    context.window.top = {};
    runInNewContext(script, context);
    assert.equal(context.HTMLMediaElement.prototype.play, original);
});
