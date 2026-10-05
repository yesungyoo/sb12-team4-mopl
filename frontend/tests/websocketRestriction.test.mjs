import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { resolve } from 'node:path';
import vm from 'node:vm';

const require = createRequire(process.env.FRONTEND_TEST_DEPENDENCIES
  ? resolve(process.env.FRONTEND_TEST_DEPENDENCIES, 'package.json') : new URL('../package.json', import.meta.url));
const ts = require('typescript');
const { create } = require('zustand');
const source = readFileSync(new URL('../src/lib/stores/websocketStore.ts', import.meta.url), 'utf8');

// 실제 서버 없이 STOMP 연결·사용자 오류·시간 경과를 재현한다.
async function setup() {
  const clients = [];
  const toasts = [];
  const consoleOutput = [];
  let now = Date.now();
  const timers = new Map();
  let timerId = 0;
  class Client {
    callbacks = new Map();
    published = [];
    deactivated = false;
    constructor(config) { Object.assign(this, config); clients.push(this); }
    activate() {}
    deactivate() { this.deactivated = true; return Promise.resolve(); }
    subscribe(destination, callback) {
      this.callbacks.set(destination, callback);
      return { unsubscribe: () => this.callbacks.delete(destination) };
    }
    publish(message) { this.published.push(message); }
  }
  class TestDate extends Date { static now() { return now; } }
  const context = vm.createContext({
    console: { log(...args) { consoleOutput.push(args.join(' ')); }, error() {}, warn() {} }, Date: TestDate,
    setTimeout: (callback, delay) => { const id = ++timerId; timers.set(id, { callback, at: now + delay }); return id; },
    clearTimeout: (id) => timers.delete(id),
  });
  const dependencies = {
    '@stomp/stompjs': { Client }, 'sockjs-client': { default: class SockJS {} },
    zustand: { create }, sonner: { toast: { error: (message) => toasts.push(message) } },
  };
  async function load(source, dependencies) {
    const module = new vm.SourceTextModule(ts.transpileModule(source, {
      compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
    }).outputText, { context, initializeImportMeta: (meta) => { meta.env = {}; } });
    await module.link((name) => new vm.SyntheticModule(Object.keys(dependencies[name]), function () {
      for (const [key, value] of Object.entries(dependencies[name])) this.setExport(key, value);
    }, { context }));
    await module.evaluate();
    return module.namespace;
  }
  const module = await load(source, dependencies);
  const store = module.useWebSocketStore;
  const actions = await load(readFileSync(new URL('../src/lib/stores/actions.ts', import.meta.url), 'utf8'), {});
  const utils = await load(readFileSync(new URL('../src/lib/stores/utils.ts', import.meta.url), 'utf8'), {});
  const authModule = await load(readFileSync(new URL('../src/lib/stores/useAuthStore.ts', import.meta.url), 'utf8'), {
    zustand: { create }, './websocketStore': module,
    '@/lib/stores/actions.ts': actions, '@/lib/stores/utils': utils,
    '@/lib/api/auth': { getCsrfToken: async () => {}, refreshToken: async () => {},
      signIn: async () => {}, signOut: async () => {} },
  });
  const auth = authModule.useAuthStore;
  auth.setState({ data: { userDto: { id: 'user-a' }, accessToken: 'test-token' } });
  await store.getState().connect('test-token');
  const client = clients[0];
  client.onConnect();
  const error = (overrides = {}) => ({ code: 'CHAT_RESTRICTED', message: '채팅 이용이 일시적으로 제한되었습니다.',
    restrictionLevel: 'TEMPORARY_SHORT', restrictedUntil: new Date(now + 60000).toISOString(), ...overrides });
  return { store, auth, client, clients, toasts, consoleOutput, error, timers, advance: (milliseconds) => {
    now += milliseconds;
    for (const [id, timer] of [...timers]) if (timer.at <= now) { timers.delete(id); timer.callback(); }
  } };
}

test('제재 없는 Chat과 DM은 기존처럼 publish한다', async () => {
  const { store, client } = await setup();
  store.getState().send('/pub/contents/id/chat', { content: '정상' });
  store.getState().send('/pub/conversations/id/direct-messages', { content: '정상' });
  assert.equal(client.published.length, 2);
});

test('사용자 오류 구독으로 level과 종료 시각을 보존하고 Chat/DM 전송을 막는다', async () => {
  const { store, client, toasts, error } = await setup();
  const payload = error();
  client.callbacks.get('/user/sub/errors')({ body: JSON.stringify(payload) });
  assert.equal(store.getState().restriction.restrictionLevel, payload.restrictionLevel);
  assert.equal(store.getState().restriction.restrictedUntil, payload.restrictedUntil);
  assert.match(toasts[0], /까지 메시지를 보낼 수 없습니다/);
  assert.match(toasts[0], /메시지 이용 정책 위반/);
  for (const destination of ['/pub/contents/id/chat', '/pub/conversations/id/direct-messages']) {
    store.getState().send(destination, { content: '전송하지 않음' });
  }
  assert.equal(client.published.length, 0);
  store.getState().send('/pub/contents/id/watch', {});
  assert.equal(client.published.length, 1);
});

test('제재 만료 시 상태가 사라지고 Chat/DM 전송이 다시 가능하다', async () => {
  const { store, client, error, advance } = await setup();
  store.getState().handleModerationError(error());
  advance(60001);
  assert.equal(store.getState().restriction, null);
  store.getState().send('/pub/contents/id/chat', {});
  store.getState().send('/pub/conversations/id/direct-messages', {});
  assert.equal(client.published.length, 2);
});

test('이미 만료됐거나 잘못된 payload가 제한 UI를 만들지 않는다', async () => {
  const { store, error } = await setup();
  for (const payload of [null, {}, error({ restrictedUntil: 'invalid' }),
    error({ restrictedUntil: new Date(0).toISOString() }), error({ restrictionLevel: 'NONE' })]) {
    store.getState().handleModerationError(payload);
  }
  assert.equal(store.getState().restriction, null);
});

test('Redis 제재 조회 오류는 재시도 안내만 보이고 restriction 상태를 만들지 않는다', async () => {
  const { store, toasts } = await setup();
  store.getState().handleModerationError({ code: 'CHAT_RESTRICTION_CHECK_FAILED',
    message: '채팅 상태를 확인하는 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.' });
  assert.equal(store.getState().restriction, null);
  assert.equal(toasts[0], '채팅 상태를 확인하는 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.');
});

test('제재 연장 시 이전 timer가 새 제재 상태를 지우지 않는다', async () => {
  const { store, error, advance } = await setup();
  store.getState().handleModerationError(error());
  store.getState().handleModerationError(error({ restrictionLevel: 'TEMPORARY_LONG',
    restrictedUntil: new Date(Date.parse(error().restrictedUntil) + 60000).toISOString() }));
  advance(60001);
  assert.equal(store.getState().restriction.restrictionLevel, 'TEMPORARY_LONG');
  advance(60001);
  assert.equal(store.getState().restriction, null);
});

test('재연결 시 오류 구독을 다시 만들고 disconnect는 제재와 timer를 정리한다', async () => {
  const { store, client, error, timers } = await setup();
  store.getState().handleModerationError(error());
  client.onWebSocketClose();
  client.onConnect();
  assert.ok(store.getState().subscriptions.has('/user/sub/errors'));
  store.getState().disconnect();
  assert.equal(store.getState().restriction, null);
  assert.equal(timers.size, 0);
});


test('늦은 짧은 제재 응답은 긴 제재와 timer를 바꾸지 않는다', async () => {
  const { store, error, advance, timers } = await setup();
  const longer = error({ restrictionLevel: 'TEMPORARY_LONG',
    restrictedUntil: new Date(Date.parse(error().restrictedUntil) + 60000).toISOString() });
  store.getState().handleModerationError(longer);
  const timer = [...timers.keys()][0];
  store.getState().handleModerationError(error());
  assert.equal(store.getState().restriction.restrictedUntil, longer.restrictedUntil);
  assert.equal([...timers.keys()][0], timer);
  advance(60001);
  assert.equal(store.getState().restriction.restrictionLevel, 'TEMPORARY_LONG');
  advance(60001);
  assert.equal(store.getState().restriction, null);
});

test('로그아웃은 실제 인증 store를 통해 연결·제재·timer를 정리한다', async () => {
  const { store, auth, client, error, timers } = await setup();
  store.getState().handleModerationError(error());
  await auth.getState().signOut();
  assert.equal(client.deactivated, true);
  assert.equal(store.getState().stompClient, null);
  assert.equal(store.getState().restriction, null);
  assert.equal(timers.size, 0);
});

test('계정 전환 후 이전 연결 callback은 새 연결과 제재를 변경하지 않는다', async () => {
  const { store, auth, client, clients, error } = await setup();
  const staleError = client.callbacks.get('/user/sub/errors');
  store.getState().handleModerationError(error());
  auth.setState({ data: { userDto: { id: 'user-b' }, accessToken: 'token-b' } });
  assert.equal(client.deactivated, true);
  await store.getState().connect('token-b');
  const next = clients[1];
  next.onConnect();
  client.onConnect(); client.onWebSocketClose(); client.onStompError({});
  staleError({ body: JSON.stringify(error()) });
  assert.equal(store.getState().stompClient, next);
  assert.equal(store.getState().isConnected, true);
  assert.equal(store.getState().restriction, null);
  store.getState().send('/pub/contents/id/chat', {});
  assert.equal(client.published.length, 0);
  assert.equal(next.published.length, 1);
});

test('같은 사용자의 token 갱신은 제재를 유지하고 다음 CONNECT 인증을 갱신한다', async () => {
  const { store, auth, client, error } = await setup();
  store.getState().handleModerationError(error());
  auth.setState({ data: { userDto: { id: 'user-a' }, accessToken: 'renewed' } });
  assert.equal(client.deactivated, false);
  assert.equal(client.connectHeaders.Authorization, 'Bearer renewed');
  assert.ok(store.getState().restriction);
});

test('연결 중·재연결 중 화면의 connect 호출은 새 Client를 만들지 않는다', async () => {
  const { store, client, clients } = await setup();
  client.onWebSocketClose();
  await Promise.all([store.getState().connect('test-token'), store.getState().connect('test-token')]);
  assert.equal(clients.length, 1);
  assert.equal(store.getState().stompClient, client);
  assert.equal(store.getState().isConnecting, true);
  assert.notEqual(client.webSocketFactory(), client.webSocketFactory());
  client.onConnect();
  assert.equal(store.getState().subscriptions.size, 1);
  assert.ok(store.getState().subscriptions.has('/user/sub/errors'));
});

test('재연결 중 로그아웃해도 자동 재연결을 중지한다', async () => {
  const { store, auth, client } = await setup();
  client.onWebSocketClose();
  await auth.getState().signOut();
  assert.equal(client.deactivated, true);
  client.onConnect();
  assert.equal(store.getState().stompClient, null);
  assert.equal(store.getState().isConnected, false);
});

test('최초 연결 중 disconnect도 client를 종료하고 늦은 연결 성공을 무시한다', async () => {
  const { store, clients } = await setup();
  store.getState().disconnect();
  await store.getState().connect('token');
  const connecting = clients[1];
  await store.getState().connect('token');
  assert.equal(clients.length, 2);
  store.getState().disconnect();
  connecting.onConnect();
  assert.equal(connecting.deactivated, true);
  assert.equal(store.getState().isConnected, false);
});


test('STOMP frame debug does not write Authorization or Bearer tokens to console', async () => {
  const { client, consoleOutput } = await setup();
  client.debug('>>> CONNECT\nAuthorization:Bearer sensitive-token');
  assert.equal(consoleOutput.some((line) => /Authorization|Bearer|sensitive-token/.test(line)), false);
});
