/**
 * DevTools page agent.
 *
 * Injected at document start, before any page script runs, so that nothing is missed during the
 * early part of the load — the gap that leaves most mobile inspectors showing an empty network
 * list until you reload.
 *
 * It complements the native recorder rather than duplicating it. Native interception sees every
 * request but never the request *body*, and cannot observe console output or page lifecycle at
 * all. This agent supplies exactly those, and reports resource timings for subresources the
 * native layer chose not to replay.
 *
 * Everything is wrapped defensively: a failure here must degrade to a missing log line, never to
 * a broken page.
 */
(function () {
    'use strict';

    var BRIDGE = '__lightningDevTools';
    var MAX_BODY_CHARS = 262144;
    var pending = [];

    if (window.__lightningDevToolsInstalled) {
        return;
    }
    window.__lightningDevToolsInstalled = true;

    function post(payload) {
        try {
            var message = JSON.stringify(payload);
            if (window[BRIDGE] && window[BRIDGE].postMessage) {
                // Drain anything buffered before the bridge object appeared.
                while (pending.length) {
                    window[BRIDGE].postMessage(pending.shift());
                }
                window[BRIDGE].postMessage(message);
            } else if (pending.length < 500) {
                pending.push(message);
            }
        } catch (e) {
            /* never let logging break the page */
        }
    }

    function truncate(text) {
        if (typeof text !== 'string') {
            return null;
        }
        return text.length > MAX_BODY_CHARS ? text.slice(0, MAX_BODY_CHARS) : text;
    }

    /**
     * Stringify console arguments without throwing on circular structures or DOM nodes, which
     * plain JSON.stringify does routinely on real pages.
     */
    function describe(value, depth) {
        depth = depth || 0;
        try {
            if (value === null) return 'null';
            if (value === undefined) return 'undefined';
            var type = typeof value;
            if (type === 'string') return value;
            if (type === 'number' || type === 'boolean') return String(value);
            if (type === 'function') return '[Function ' + (value.name || 'anonymous') + ']';
            if (value instanceof Error) return value.stack || (value.name + ': ' + value.message);
            if (value instanceof Element) {
                return '<' + value.tagName.toLowerCase() +
                    (value.id ? ' id="' + value.id + '"' : '') + '>';
            }
            if (depth > 2) return Array.isArray(value) ? '[Array]' : '[Object]';
            if (Array.isArray(value)) {
                return '[' + value.slice(0, 50).map(function (v) {
                    return describe(v, depth + 1);
                }).join(', ') + ']';
            }
            var parts = [];
            for (var key in value) {
                if (Object.prototype.hasOwnProperty.call(value, key)) {
                    if (parts.length >= 50) { parts.push('...'); break; }
                    parts.push(key + ': ' + describe(value[key], depth + 1));
                }
            }
            return '{' + parts.join(', ') + '}';
        } catch (e) {
            return '[unserializable]';
        }
    }

    function headersToList(headerString) {
        var list = [];
        if (!headerString) return list;
        headerString.trim().split(/[\r\n]+/).forEach(function (line) {
            var index = line.indexOf(':');
            if (index > 0) {
                list.push({
                    name: line.slice(0, index).trim(),
                    value: line.slice(index + 1).trim()
                });
            }
        });
        return list;
    }

    /* ---------------------------------------------------------------- console */

    ['log', 'info', 'warn', 'error', 'debug'].forEach(function (level) {
        var original = console[level];
        console[level] = function () {
            try {
                var args = Array.prototype.slice.call(arguments);
                post({
                    type: 'console',
                    level: level,
                    text: args.map(function (a) { return describe(a); }).join(' '),
                    timestamp: Date.now()
                });
            } catch (e) { /* ignore */ }
            if (original) {
                return original.apply(console, arguments);
            }
        };
    });

    window.addEventListener('error', function (event) {
        post({
            type: 'console',
            level: 'error',
            text: (event.message || 'Script error') +
                (event.filename ? ' (' + event.filename + ':' + event.lineno + ')' : ''),
            timestamp: Date.now()
        });
    });

    window.addEventListener('unhandledrejection', function (event) {
        post({
            type: 'console',
            level: 'error',
            text: 'Unhandled promise rejection: ' + describe(event.reason),
            timestamp: Date.now()
        });
    });

    /* ------------------------------------------------------------ XHR capture */

    var XhrProto = XMLHttpRequest.prototype;
    var originalOpen = XhrProto.open;
    var originalSend = XhrProto.send;

    XhrProto.open = function (method, url) {
        this.__dt = { method: method, url: url, start: Date.now() };
        return originalOpen.apply(this, arguments);
    };

    XhrProto.send = function (body) {
        var self = this;
        if (self.__dt) {
            self.__dt.requestBody = truncate(typeof body === 'string' ? body : null);
            self.addEventListener('loadend', function () {
                try {
                    var responseText = null;
                    // Reading responseText throws for arraybuffer/blob response types.
                    if (!self.responseType || self.responseType === 'text') {
                        responseText = truncate(self.responseText);
                    }
                    post({
                        type: 'xhr',
                        method: self.__dt.method,
                        url: self.__dt.url,
                        requestBody: self.__dt.requestBody,
                        status: self.status,
                        statusText: self.statusText,
                        responseHeaders: headersToList(self.getAllResponseHeaders()),
                        mimeType: self.getResponseHeader('Content-Type') || '',
                        responseBody: responseText,
                        startedAt: self.__dt.start,
                        durationMillis: Date.now() - self.__dt.start
                    });
                } catch (e) { /* ignore */ }
            });
        }
        return originalSend.apply(this, arguments);
    };

    /* ---------------------------------------------------------- fetch capture */

    if (window.fetch) {
        var originalFetch = window.fetch;
        window.fetch = function (input, init) {
            var start = Date.now();
            var url = typeof input === 'string' ? input : (input && input.url) || '';
            var method = (init && init.method) ||
                (typeof input !== 'string' && input && input.method) || 'GET';
            var requestBody = init && typeof init.body === 'string'
                ? truncate(init.body)
                : null;

            return originalFetch.apply(this, arguments).then(function (response) {
                try {
                    var headers = [];
                    response.headers.forEach(function (value, name) {
                        headers.push({ name: name, value: value });
                    });
                    // Clone before the caller consumes the body; reading the original would
                    // leave the page with an already-used stream.
                    response.clone().text().then(function (text) {
                        post({
                            type: 'fetch',
                            method: method,
                            url: url || response.url,
                            requestBody: requestBody,
                            status: response.status,
                            statusText: response.statusText,
                            responseHeaders: headers,
                            mimeType: response.headers.get('Content-Type') || '',
                            responseBody: truncate(text),
                            startedAt: start,
                            durationMillis: Date.now() - start
                        });
                    })['catch'](function () { /* opaque or streamed body */ });
                } catch (e) { /* ignore */ }
                return response;
            })['catch'](function (error) {
                post({
                    type: 'fetch',
                    method: method,
                    url: url,
                    requestBody: requestBody,
                    status: 0,
                    statusText: '',
                    responseHeaders: [],
                    mimeType: '',
                    responseBody: null,
                    startedAt: start,
                    durationMillis: Date.now() - start,
                    error: String(error)
                });
                throw error;
            });
        };
    }

    /* ------------------------------------------------------- resource timings */

    if (window.PerformanceObserver) {
        try {
            var observer = new PerformanceObserver(function (list) {
                list.getEntries().forEach(function (entry) {
                    post({
                        type: 'resource',
                        url: entry.name,
                        initiatorType: entry.initiatorType,
                        protocol: entry.nextHopProtocol || '',
                        startTime: entry.startTime,
                        duration: entry.duration,
                        // Zero for cross-origin resources without Timing-Allow-Origin. The native
                        // recorder has no such restriction, so it fills these gaps in.
                        domainLookupStart: entry.domainLookupStart,
                        domainLookupEnd: entry.domainLookupEnd,
                        connectStart: entry.connectStart,
                        connectEnd: entry.connectEnd,
                        secureConnectionStart: entry.secureConnectionStart,
                        requestStart: entry.requestStart,
                        responseStart: entry.responseStart,
                        responseEnd: entry.responseEnd,
                        transferSize: entry.transferSize,
                        encodedBodySize: entry.encodedBodySize,
                        decodedBodySize: entry.decodedBodySize,
                        timeOrigin: performance.timeOrigin || 0
                    });
                });
            });
            // buffered:true replays entries that landed before this observer was created.
            observer.observe({ type: 'resource', buffered: true });
        } catch (e) { /* ignore */ }
    }

    /* --------------------------------------------------------- page lifecycle */

    function reportPageTimings() {
        try {
            var nav = performance.getEntriesByType('navigation')[0];
            post({
                type: 'pageTimings',
                url: location.href,
                title: document.title,
                onContentLoad: nav ? nav.domContentLoadedEventEnd : -1,
                onLoad: nav ? nav.loadEventEnd : -1
            });
        } catch (e) { /* ignore */ }
    }

    document.addEventListener('DOMContentLoaded', reportPageTimings);
    window.addEventListener('load', function () {
        // loadEventEnd is only final once the load handler has returned.
        setTimeout(reportPageTimings, 0);
    });
}());
