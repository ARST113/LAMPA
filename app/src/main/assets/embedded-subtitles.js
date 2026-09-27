(function () {
    'use strict';
    if (window.__lampaNativeSubsInstalled) return;
    window.__lampaNativeSubsInstalled = true;

    var state = {item: null, url: '', cues: [], seen: {}, session: 0, lastClock: -1};
    function send(payload) {
        payload.session = state.session;
        window.cefriumQuery({request: JSON.stringify(payload), onSuccess: function () {}, onFailure: function (code, message) {
            console.warn('[LAMPA subs] bridge: ' + code + ' ' + message);
        }});
    }
    function video() {
        return window.Lampa && Lampa.PlayerVideo && Lampa.PlayerVideo.video ? Lampa.PlayerVideo.video() : null;
    }
    function mediaUrl() {
        var data = window.Lampa && Lampa.Player && Lampa.Player.playdata ? Lampa.Player.playdata() : null;
        var el = video();
        return data && typeof data.url === 'string' ? data.url : el && (el.currentSrc || el.src) || '';
    }
    function position() {
        var el = video();
        return el && Number.isFinite(el.currentTime) ? Math.max(0, Math.round(el.currentTime * 1000)) : 0;
    }
    function clearText() {
        var box = document.querySelector('.player-video__subtitles');
        var target = box && box.querySelector('div');
        if (target) { target.textContent = ''; target.style.display = 'none'; }
    }
    function paint() {
        if (!state.item) return;
        var box = document.querySelector('.player-video__subtitles');
        var target = box && box.querySelector('div');
        if (!target) return;
        var at = position();
        var lines = state.cues.filter(function (cue) { return cue[0] <= at && at < cue[1]; })
            .map(function (cue) { return cue[2]; });
        box.classList.remove('hide');
        target.textContent = lines.join('\n');
        target.style.whiteSpace = 'pre-line';
        target.style.display = lines.length ? 'inline-block' : 'none';
    }
    function stop() {
        if (!state.item) return;
        state.item = null;
        state.cues = [];
        state.seen = {};
        state.session++;
        send({type: 'subs-stop'});
        clearText();
    }
    function start(item) {
        var ordinal = Number(item.index);
        var url = mediaUrl();
        if (!Number.isInteger(ordinal) || ordinal < 0 || !/^https?:\/\//i.test(url)) return;
        if (state.item === item && state.url === url) return;
        state.session++;
        if (url !== state.url) {
            state.url = url;
            send({type: 'subs-open', url: url, position: position()});
        }
        state.item = item;
        state.cues = [];
        state.seen = {};
        state.lastClock = -1;
        // Selecting a track is independent of the playback clock (including while paused).
        send({type: 'subs-select', url: url, ordinal: ordinal,
            language: item.language || '', label: item.label || '', position: position()});
        console.log('[LAMPA subs] selected menu track ' + ordinal);
        paint();
    }
    function prepare(items) {
        var el = video();
        if (!el || el.textTracks && el.textTracks.length) return items;
        (items || []).forEach(function (item, index) {
            // URL subtitles already have a working loader; preserve their callbacks.
            if (!item || item.__lampaNativeSub || item.url || item.is_url || !Number.isInteger(Number(item.index))) return;
            var descriptor = Object.getOwnPropertyDescriptor(item, 'mode');
            if (descriptor && descriptor.configurable === false) {
                // tracks.js creates a non-configurable mode accessor. Lampa's option
                // handler retains the array, so replace only this entry, keeping its
                // metadata and callbacks while giving the bridge its own mode setter.
                var properties = Object.getOwnPropertyDescriptors(item);
                delete properties.mode;
                item = Object.create(Object.getPrototypeOf(item), properties);
                items[index] = item;
            }
            Object.defineProperty(item, '__lampaNativeSub', {value: true});
            Object.defineProperty(item, 'mode', {configurable: true,
                get: function () { return state.item === item ? 'showing' : 'disabled'; },
                set: function (value) {
                    if (value === 'showing') {
                        if (Number(item.index) < 0) stop(); else start(item);
                    } else if (state.item === item) stop();
                }});
            item.ghost = false;
        });
        return items;
    }
    function bind() {
        var el = video();
        if (!el || el.__lampaNativeSubsBound) return;
        el.__lampaNativeSubsBound = true;
        el.addEventListener('timeupdate', function () {
            paint();
            if (state.item && Math.abs(position() - state.lastClock) > 4000) {
                state.lastClock = position();
                send({type: 'subs-time', position: position()});
            }
        });
        el.addEventListener('seeking', function () {
            if (!state.item) return;
            state.session++;
            state.cues = [];
            state.seen = {};
            send({type: 'subs-seek', position: position()});
            paint();
        });
        el.addEventListener('seeked', paint);
        el.addEventListener('ended', stop);
        el.addEventListener('emptied', stop);
    }
    function hook() {
        if (!window.Lampa) return;
        var panel = Lampa.PlayerPanel;
        if (panel && panel.setSubs && !panel.__lampaNativeSubsWrapped) {
            var original = panel.setSubs;
            panel.setSubs = function (items) { return original.call(this, prepare(items)); };
            panel.__lampaNativeSubsWrapped = true;
        }
        var listener = Lampa.PlayerVideo && Lampa.PlayerVideo.listener;
        if (listener && !listener.__lampaNativeSubsWrapped) {
            // Internal Option.setSubtitles retains the same array; mutate its items in place.
            listener.follow('subs', function (event) { prepare(event.subs); });
            listener.__lampaNativeSubsWrapped = true;
        }
        var select = Lampa.Select;
        if (select && select.show && !select.__lampaNativeSubsWrapped) {
            var show = select.show;
            select.show = function (params) {
                if (params && Lampa.Lang && params.title === Lampa.Lang.translate('player_subs')) prepare(params.items);
                return show.apply(this, arguments);
            };
            select.__lampaNativeSubsWrapped = true;
        }
    }
    window.__lampaNativeSubs = {
        stop: stop,
        onNative: function (raw) {
            var message;
            try { message = JSON.parse(raw); } catch (_) { return; }
            if (message.session !== undefined && message.session !== state.session) return;
            if (message.type === 'cues' && state.item && Number(message.ordinal) === Number(state.item.index)) {
                (message.cues || []).forEach(function (cue) {
                    if (!Number.isFinite(cue[0]) || !Number.isFinite(cue[1]) || cue[1] <= cue[0] || typeof cue[2] !== 'string') return;
                    var key = JSON.stringify(cue);
                    if (!state.seen[key]) { state.seen[key] = true; state.cues.push(cue); }
                });
                paint();
            } else if (message.type === 'error') console.warn('[LAMPA subs] ' + message.message);
            else if (message.type === 'selected') console.log('[LAMPA subs] native track ' + message.ordinal + ' ' + message.mime);
        }
    };
    setInterval(function () {
        hook(); bind();
        if (state.item && state.url !== mediaUrl()) stop();
        paint();
    }, 1000);
    hook(); bind();
})();
