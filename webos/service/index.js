// webOS JS service: sends the app's Plex requests from Node, where the browser's CORS rules do not apply.
// The friend's Plex server only allows browser requests from app.plex.tv, so a TV app needs this path.
// Old TVs run an old Node (webOS 5: Node 12), so this file avoids newer syntax.
var Service = require('webos-service');
var http = require('http');
var https = require('https');
var urlLib = require('url');

var service = new Service('com.dkrb56.bufferzone.service');

function send(url, method, headers, timeout, redirects, done) {
  var u = urlLib.parse(url);
  var lib = u.protocol === 'http:' ? http : https;
  var req = lib.request({
    protocol: u.protocol, hostname: u.hostname, port: u.port, path: u.path,
    method: method, headers: headers
  }, function (res) {
    if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && redirects < 3) {
      res.resume();
      return send(urlLib.resolve(url, res.headers.location), method, headers, timeout, redirects + 1, done);
    }
    var chunks = [];
    res.on('data', function (c) { chunks.push(c); });
    res.on('end', function () {
      done({ ok: res.statusCode >= 200 && res.statusCode < 300, status: res.statusCode, body: Buffer.concat(chunks).toString('utf8') });
    });
    res.on('error', function (e) { done({ ok: false, status: 0, body: String(e && e.message || e) }); });
  });
  req.setTimeout(timeout, function () { req.abort(); });
  req.on('error', function (e) { done({ ok: false, status: 0, body: String(e && e.message || e) }); });
  req.end();
}

service.register('http', function (message) {
  var p = message.payload || {};
  if (!p.url || !/^https?:\/\//.test(p.url)) return message.respond({ returnValue: false, errorText: 'url must be http or https' });
  var finished = false;
  send(p.url, p.method || 'GET', p.headers || {}, p.timeout || 15000, 0, function (r) {
    if (finished) return;
    finished = true;
    r.returnValue = true;
    message.respond(r);
  });
});
