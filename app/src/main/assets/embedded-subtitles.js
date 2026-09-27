(function () {
    'use strict';
    if (window.__lampaNativeSubsInstalled) return;
    window.__lampaNativeSubsInstalled = true;

    var state = {item: null, url: '', cues: [], seen: {}, session: 0, lastClock: -1,
        sourceUrl: '', sourceVideo: null, probeUrl: '', menuUrl: '', menuVideo: null, menuItems: []};
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
        // Lampa keeps &preload in playdata, but assigns the actual &play URL to the video.
        var raw = el && /^https?:\/\//i.test(el.currentSrc || '') ? el.currentSrc :
            el && /^https?:\/\//i.test(el.src || '') ? el.src :
            data && typeof data.url === 'string' ? data.url : el && (el.currentSrc || el.src) || '';
        return window.__lampaSubtitleProxy ? window.__lampaSubtitleProxy.originalUrl(raw) : raw;
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
        if (!state.item && !state.probeUrl) return;
        var wasShowing = !!state.item;
        state.item = null;
        state.probeUrl = '';
        state.cues = [];
        state.seen = {};
        state.session++;
        send({type: 'subs-stop'});
        if (wasShowing) clearText();
    }
    function syncSource() {
        var el = video();
        var url = mediaUrl();
        if (state.sourceUrl !== url || state.sourceVideo !== el) {
            if (state.item || state.probeUrl) stop(); else state.session++;
            state.sourceUrl = url;
            state.sourceVideo = el;
            state.probeUrl = '';
            state.url = '';
        }
        return url;
    }
    function hasOriginalSubtitles() {
        var el = video();
        var data = window.Lampa && Lampa.Player && Lampa.Player.playdata ? Lampa.Player.playdata() : null;
        return el && ((el.textTracks && el.textTracks.length) || (el.customSubs && el.customSubs.length)) ||
            data && data.subtitles && data.subtitles.length;
    }
    function hasMenu(url) {
        return state.menuUrl === url && state.menuVideo === video() && state.menuItems.some(function (item) {
            return item && Number.isInteger(Number(item.index)) && Number(item.index) >= 0;
        });
    }
    function discover() {
        var url = syncSource();
        if (!video() || !/^https?:\/\//i.test(url) || !window.Lampa || !Lampa.PlayerPanel || !Lampa.PlayerPanel.setSubs ||
            state.item || state.probeUrl === url || hasOriginalSubtitles() || hasMenu(url)) return;
        state.probeUrl = url;
        send({type: 'subs-open', url: url, position: position(), probe: true});
    }
    function start(item) {
        var ordinal = Number(item.index);
        var url = syncSource();
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
        // Lampa can replace the whole menu when a provider's subtitles arrive later.
        // Its selection handler only disables entries in that new array.
        if (state.item && (!items || items.indexOf(state.item) === -1)) stop();
        state.menuUrl = mediaUrl();
        state.menuVideo = el;
        state.menuItems = items || [];
        if (!el || el.textTracks && el.textTracks.length) return items;
        (items || []).forEach(function (item, index) {
            // URL subtitles already have a working loader; preserve their callbacks.
            if (!item || item.__lampaNativeSub || item.url || item.is_url || !Number.isInteger(Number(item.index))) return;
            var descriptor = Object.getOwnPropertyDescriptor(item, 'mode');
            // HLS uses its own mode accessor while textTracks is empty. Only ghost
            // metadata accessors need replacement; preserve working provider modes.
            if (descriptor && (descriptor.get || descriptor.set) && !item.ghost) return;
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
        el.addEventListener('loadedmetadata', discover);
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
            if (!message) return;
            if (message.session !== undefined && message.session !== state.session) return;
            if (message.url !== undefined && message.url !== mediaUrl()) return;
            if (message.type === 'tracks' && message.url === state.probeUrl && !state.item &&
                !hasOriginalSubtitles() && !hasMenu(message.url)) {
                var items = (message.tracks || []).filter(function (track) {
                    return track.mime === 'S_TEXT/UTF8' && Number.isInteger(Number(track.ordinal)) && Number(track.ordinal) >= 0;
                }).map(function (track) {
                    return {index: Number(track.ordinal), language: track.language || '', label: track.label || '',
                        ghost: false, selected: false};
                });
                // Option.setSubtitles exposes the button, and its existing onSelect sets mode.
                if (items.length) Lampa.PlayerPanel.setSubs(items);
            } else if (message.type === 'cues' && state.item && Number(message.ordinal) === Number(state.item.index)) {
                if (message.replace) { state.cues = []; state.seen = {}; }
                (message.cues || []).forEach(function (cue) {
                    if (!Number.isFinite(cue[0]) || !Number.isFinite(cue[1]) || cue[1] <= cue[0] || typeof cue[2] !== 'string' || cue[2].length > 65536) return;
                    var key = JSON.stringify(cue);
                    if (!state.seen[key]) { state.seen[key] = true; state.cues.push(cue); }
                });
                var retained = [], chars = 0, at = position();
                for (var i = state.cues.length - 1; i >= 0 && retained.length < 12000; i--) {
                    var row = state.cues[i];
                    if (row[1] <= at || row[0] > at + 120000 || chars + row[2].length > 2000000) continue;
                    retained.push(row); chars += row[2].length;
                }
                state.cues = retained.reverse(); state.seen = {};
                state.cues.forEach(function (cue) { state.seen[JSON.stringify(cue)] = true; });
                paint();
            } else if (message.type === 'error') console.warn('[LAMPA subs] ' + message.message);
            else if (message.type === 'selected') console.log('[LAMPA subs] native track ' + message.ordinal + ' ' + message.mime);
        }
    };
    setInterval(function () {
        hook(); bind();
        discover();
        paint();
    }, 1000);
    hook(); bind(); discover();
})();
