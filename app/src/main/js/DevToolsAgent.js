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

    /**
     * Resolve a possibly relative URL against the document, so entries can be matched against the
     * native recorder's absolute URLs. Without this a form posting to "/api/user/login/" never
     * lines up with the native entry for the same request.
     */
    function absolute(url) {
        try {
            return new URL(url, location.href).href;
        } catch (e) {
            return url;
        }
    }

    /**
     * Resolve a request body of any accepted type to text, as a promise.
     *
     * Capturing only `typeof body === 'string'` missed most analytics traffic: Google Analytics
     * and DoubleClick send `Blob`, `URLSearchParams` or a typed array, and those all arrived with
     * no body recorded at all. Blob is read asynchronously, which is why this returns a promise
     * rather than a value.
     */
    function bodyToText(body) {
        try {
            if (body === null || body === undefined) {
                return Promise.resolve(null);
            }
            if (typeof body === 'string') {
                return Promise.resolve(truncate(body));
            }
            if (typeof URLSearchParams !== 'undefined' && body instanceof URLSearchParams) {
                return Promise.resolve(truncate(body.toString()));
            }
            if (typeof FormData !== 'undefined' && body instanceof FormData) {
                var parts = [];
                body.forEach(function (value, name) {
                    parts.push(
                        encodeURIComponent(name) + '=' +
                        (typeof value === 'string' ? encodeURIComponent(value) : '[file]')
                    );
                });
                return Promise.resolve(truncate(parts.join('&')));
            }
            if (typeof Blob !== 'undefined' && body instanceof Blob) {
                if (typeof body.text === 'function') {
                    return body.text().then(truncate)['catch'](function () {
                        return '[Blob ' + body.size + ' bytes]';
                    });
                }
                return Promise.resolve('[Blob ' + body.size + ' bytes]');
            }
            if (typeof ArrayBuffer !== 'undefined' && body instanceof ArrayBuffer) {
                return Promise.resolve('[ArrayBuffer ' + body.byteLength + ' bytes]');
            }
            if (typeof ArrayBuffer !== 'undefined' && ArrayBuffer.isView(body)) {
                return Promise.resolve('[binary ' + body.byteLength + ' bytes]');
            }
            return Promise.resolve(truncate(String(body)));
        } catch (e) {
            return Promise.resolve(null);
        }
    }

    /** Normalise fetch's several accepted header shapes into a name/value list. */
    function normalizeHeaders(headers) {
        var list = [];
        if (!headers) return list;
        try {
            if (typeof headers.forEach === 'function' && !Array.isArray(headers)) {
                headers.forEach(function (value, name) {
                    list.push({ name: String(name), value: String(value) });
                });
            } else if (Array.isArray(headers)) {
                headers.forEach(function (pair) {
                    if (pair && pair.length >= 2) {
                        list.push({ name: String(pair[0]), value: String(pair[1]) });
                    }
                });
            } else {
                Object.keys(headers).forEach(function (name) {
                    list.push({ name: name, value: String(headers[name]) });
                });
            }
        } catch (e) { /* ignore */ }
        return list;
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

    var originalSetRequestHeader = XhrProto.setRequestHeader;

    XhrProto.open = function (method, url) {
        this.__dt = {
            method: method,
            url: absolute(url),
            start: Date.now(),
            requestHeaders: []
        };
        return originalOpen.apply(this, arguments);
    };

    // The only way to see an XHR's request headers: there is no getter for them.
    XhrProto.setRequestHeader = function (name, value) {
        try {
            if (this.__dt) {
                this.__dt.requestHeaders.push({ name: String(name), value: String(value) });
            }
        } catch (e) { /* ignore */ }
        return originalSetRequestHeader.apply(this, arguments);
    };

    XhrProto.send = function (body) {
        var self = this;
        if (self.__dt) {
            self.__dt.bodyPromise = bodyToText(body);
            self.addEventListener('loadend', function () {
                try {
                    var responseText = null;
                    // Reading responseText throws for arraybuffer/blob response types.
                    if (!self.responseType || self.responseType === 'text') {
                        responseText = truncate(self.responseText);
                    }
                    var status = self.status;
                    var responseHeaders = headersToList(self.getAllResponseHeaders());
                    var mimeType = self.getResponseHeader('Content-Type') || '';
                    self.__dt.bodyPromise.then(function (requestBody) {
                        post({
                            type: 'xhr',
                            method: self.__dt.method,
                            url: self.__dt.url,
                            requestHeaders: self.__dt.requestHeaders,
                            requestBody: requestBody,
                            status: status,
                            statusText: self.statusText,
                            responseHeaders: responseHeaders,
                            mimeType: mimeType,
                            responseBody: responseText,
                            startedAt: self.__dt.start,
                            durationMillis: Date.now() - self.__dt.start
                        });
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
            var url = absolute(typeof input === 'string' ? input : (input && input.url) || '');
            var method = (init && init.method) ||
                (typeof input !== 'string' && input && input.method) || 'GET';
            var bodyPromise = bodyToText(
                (init && init.body) ||
                (typeof input !== 'string' && input && input.body) ||
                null
            );
            // Headers may be given on the init object or baked into a Request instance.
            var requestHeaders = normalizeHeaders(
                (init && init.headers) ||
                (typeof input !== 'string' && input && input.headers) ||
                null
            );

            return originalFetch.apply(this, arguments).then(function (response) {
                try {
                    var headers = [];
                    response.headers.forEach(function (value, name) {
                        headers.push({ name: name, value: value });
                    });
                    // Clone before the caller consumes the body; reading the original would
                    // leave the page with an already-used stream.
                    Promise.all([
                        response.clone().text()['catch'](function () { return null; }),
                        bodyPromise
                    ]).then(function (results) {
                        post({
                            type: 'fetch',
                            method: method,
                            url: url || response.url,
                            requestHeaders: requestHeaders,
                            requestBody: results[1],
                            status: response.status,
                            statusText: response.statusText,
                            responseHeaders: headers,
                            mimeType: response.headers.get('Content-Type') || '',
                            responseBody: truncate(results[0]),
                            startedAt: start,
                            durationMillis: Date.now() - start,
                            // A cross-origin no-cors fetch yields an opaque response: status 0
                            // and no headers, by design. Flagging it stops that looking like a
                            // failure in the panel.
                            opaque: response.type === 'opaque'
                        });
                    })['catch'](function () { /* ignore */ });
                } catch (e) { /* ignore */ }
                return response;
            })['catch'](function (error) {
                bodyPromise.then(function (requestBody) {
                post({
                    type: 'fetch',
                    method: method,
                    url: url,
                    requestHeaders: requestHeaders,
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
                });
                throw error;
            });
        };
    }

    /* ------------------------------------------------------ sendBeacon capture */

    if (navigator.sendBeacon) {
        var originalSendBeacon = navigator.sendBeacon.bind(navigator);
        navigator.sendBeacon = function (url, data) {
            var start = Date.now();
            var queued = originalSendBeacon(url, data);
            try {
                bodyToText(data).then(function (requestBody) {
                    post({
                        type: 'fetch',
                        method: 'POST',
                        url: absolute(url),
                        requestHeaders: [],
                        requestBody: requestBody,
                        // The Beacon API returns only whether the send was queued; there is no
                        // response to observe. The native layer records the request headers.
                        status: 0,
                        statusText: queued ? 'queued' : 'rejected',
                        responseHeaders: [],
                        mimeType: '',
                        responseBody: null,
                        startedAt: start,
                        durationMillis: 0,
                        beacon: true,
                        error: queued ? null : 'sendBeacon refused the payload'
                    });
                });
            } catch (e) { /* ignore */ }
            return queued;
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
