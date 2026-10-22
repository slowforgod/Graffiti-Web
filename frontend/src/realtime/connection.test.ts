import type { Client, IFrame, IMessage, StompHeaders } from '@stomp/stompjs';
import { describe, expect, it, vi } from 'vitest';
import { brokerUrl, createConnection } from './connection';

class FakeClient {
  connected = false;
  reconnectDelay = 0;
  heartbeatIncoming = 0;
  heartbeatOutgoing = 0;
  onConnect: () => void = () => {};
  onStompError: (frame: IFrame) => void = () => {};
  receipts = new Map<string, () => void>();
  subscribed: { destination: string; headers: StompHeaders; callback: (m: IMessage) => void }[] = [];
  published: { destination: string; body: string }[] = [];
  activate = vi.fn();
  deactivate = vi.fn(async () => {});

  watchForReceipt(id: string, cb: () => void) {
    this.receipts.set(id, cb);
  }

  subscribe(destination: string, callback: (m: IMessage) => void, headers: StompHeaders) {
    this.subscribed.push({ destination, headers, callback });
    return { id: 'sub', unsubscribe: vi.fn() };
  }

  publish(p: { destination: string; body: string }) {
    this.published.push(p);
  }
}

function make() {
  const client = new FakeClient();
  const connection = createConnection(() => client as unknown as Client);
  return { client, connection };
}

describe('connection', () => {
  it('builds the same-origin broker url', () => {
    expect(brokerUrl({ protocol: 'https:', host: 'g.example' })).toBe('wss://g.example/ws');
    expect(brokerUrl({ protocol: 'http:', host: 'localhost:5173' })).toBe('ws://localhost:5173/ws');
  });

  it('asks for a receipt on every subscription and parses messages', () => {
    const { client, connection } = make();
    const onMessage = vi.fn();
    const onReady = vi.fn();
    connection.subscribe('/topic/x', onMessage, onReady);

    const sub = client.subscribed[0]!;
    expect(sub.headers.receipt).toBeDefined();
    client.receipts.get(sub.headers.receipt!)!();
    expect(onReady).toHaveBeenCalled();
    sub.callback({ body: '{"type":"a"}' } as IMessage);
    expect(onMessage).toHaveBeenCalledWith({ type: 'a' });
  });

  it('sends only while connected', () => {
    const { client, connection } = make();
    connection.send('/app/cursor', { x: 1 });
    client.connected = true;
    connection.send('/app/cursor', { x: 2 });
    expect(client.published).toEqual([{ destination: '/app/cursor', body: '{"x":2}' }]);
  });

  it('tells listeners about connects and PAGE_ENDED errors', () => {
    const { client, connection } = make();
    const onConnect = vi.fn();
    const onPageEnded = vi.fn();
    connection.onConnect(onConnect);
    connection.onPageEnded(onPageEnded);

    client.onConnect();
    client.onStompError({ headers: { message: 'FORBIDDEN' } } as unknown as IFrame);
    client.onStompError({ headers: { message: 'PAGE_ENDED' } } as unknown as IFrame);
    expect(onConnect).toHaveBeenCalledTimes(1);
    expect(onPageEnded).toHaveBeenCalledTimes(1);
  });

  it('uses the server heartbeat', () => {
    const { client, connection } = make();
    connection.activate(10000);
    expect(client.heartbeatIncoming).toBe(10000);
    expect(client.heartbeatOutgoing).toBe(10000);
    expect(client.activate).toHaveBeenCalled();
  });
});
