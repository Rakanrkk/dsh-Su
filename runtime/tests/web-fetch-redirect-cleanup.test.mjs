import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const base = join(resolve(process.env.DSH_PREFIX), 'node_modules/@deepseek-ai');
const { HttpFetchProvider } = await import(pathToFileURL(join(base, 'dsh-web-fetch-http/lib/index.js')));
const { WebError } = await import(pathToFileURL(join(base, 'dsh-web/lib/index.js')));

async function fixture(t, location, maxRedirects = 3) {
  const paths = [];
  const server = http.createServer((req, res) => {
    paths.push(req.url);
    if (req.url === '/final') {
      res.writeHead(200, { 'content-type': 'text/plain' });
      res.end('complete');
      return;
    }
    res.writeHead(302, { 'content-type': 'text/plain', ...(location === undefined ? {} : { location }) });
    // A valid persistent response: the provider must cancel before closing its Agent.
    res.write('redirect body remains open');
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const controller = new AbortController(), closeEntered = Promise.withResolvers();
  let pending;
  t.after(async () => {
    // Rescue the original unread-body path after a red assertion; never await a deadline.
    controller.abort(new Error('fixture cleanup'));
    if (pending) await pending;
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  });
  const provider = new HttpFetchProvider({
    timeoutMs: 30000, maxRedirects, maxResponseBytes: 1024, maxBodyChars: 1024, userAgent: 'redirect-cleanup-test',
  }, async () => [{ address: '127.0.0.1', family: 4 }]);
  // The documented resolver seam directs an otherwise public hostname to this
  // test server. Request, Response, body cancellation and undici Agent are real.
  const requestOnce = provider.requestOnce, responses = [];
  provider.requestOnce = async function (...args) {
    const request = await requestOnce.apply(this, args);
    const item = { cancellations: 0, closes: 0 };
    responses.push(item);
    if (request.response.body) {
      const cancel = request.response.body.cancel.bind(request.response.body);
      request.response.body.cancel = (...args) => { item.cancellations++; return cancel(...args); };
    }
    const close = request.close;
    request.close = () => { item.closes++; closeEntered.resolve(); return close(); };
    return request;
  };
  const url = `http://example.test:${server.address().port}/redirect`;
  pending = provider.fetch({ url }, controller.signal).then(value => ({ value }), error => ({ error }));
  return { paths, responses, closeEntered: closeEntered.promise, pending };
}

for (const location of ['http://[', 'http://example.test:bad-port/']) {
  test(`malformed Location ${location} cancels its real response before Agent.close`, async t => {
    const f = await fixture(t, location);
    await f.closeEntered;
    t.diagnostic(`malformed Location: body.cancel=${f.responses[0].cancellations} close=${f.responses[0].closes}`);
    assert.equal(f.responses[0].cancellations, 1);
    const { error } = await f.pending;
    assert.ok(error instanceof WebError);
    assert.equal(error.code, 'WEB_PROVIDER_ERROR');
    assert.equal(error.message, `invalid redirect Location "${location}"`);
    assert.ok(error.cause instanceof TypeError);
    assert.equal(error.cause.code, 'ERR_INVALID_URL');
    assert.deepEqual(f.paths, ['/redirect']);
  });
}

test('same-origin relative redirect still cancels the old body and reads the final response', async t => {
  const f = await fixture(t, '/final');
  const result = await f.pending;
  assert.equal(result.error, undefined);
  assert.equal(result.value.statusCode, 200);
  assert.deepEqual(f.paths, ['/redirect', '/final']);
  assert.deepEqual(f.responses.map(item => item.cancellations), [1, 0]);
  assert.deepEqual(f.responses.map(item => item.closes), [1, 1]);
});

for (const [name, location, cap, code] of [
  ['cross-origin', 'http://other.example.test/final', 3, 'WEB_REDIRECT_BLOCKED'],
  ['missing Location', undefined, 3, 'WEB_PROVIDER_ERROR'],
  ['hop cap', '/final', 0, 'WEB_REDIRECT_BLOCKED'],
]) {
  test(`${name} retains its rejection and response cancellation`, async t => {
    const f = await fixture(t, location, cap);
    const { error } = await f.pending;
    assert.ok(error instanceof WebError);
    assert.equal(error.code, code);
    assert.equal(f.responses[0].cancellations, 1);
    assert.deepEqual(f.paths, ['/redirect']);
  });
}
