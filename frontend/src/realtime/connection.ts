import { Client } from '@stomp/stompjs';
import type { RealtimeLink } from './link';

// Waits this long before reconnecting after the socket drops.
const RECONNECT_DELAY_MS = 2000;

export interface Connection extends RealtimeLink {
  activate(heartbeatMs: number): void;
  deactivate(): void;
}

export function brokerUrl(location: Pick<Location, 'protocol' | 'host'>): string {
  return `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`;
}

// STOMP over the same-origin /ws (D-02). The session cookie identifies the user; anonymous
// connections may only subscribe (API.md §6).
export function createConnection(factory: (url: string) => Client = (url) => new Client({ brokerURL: url })): Connection {
  const client = factory(brokerUrl(window.location));
  const connectListeners = new Set<() => void>();
  const pageEndedListeners = new Set<() => void>();
  let receiptSeq = 0;

  client.reconnectDelay = RECONNECT_DELAY_MS;
  client.onConnect = () => connectListeners.forEach((l) => l());
  client.onStompError = (frame) => {
    if (frame.headers.message === 'PAGE_ENDED') {
      pageEndedListeners.forEach((l) => l());
    }
  };

  return {
    activate(heartbeatMs) {
      client.heartbeatIncoming = heartbeatMs;
      client.heartbeatOutgoing = heartbeatMs;
      client.activate();
    },
    deactivate() {
      void client.deactivate();
    },
    connected: () => client.connected,
    subscribe(destination, onMessage, onReady) {
      const receipt = `r-${++receiptSeq}`;
      client.watchForReceipt(receipt, onReady);
      const sub = client.subscribe(destination, (frame) => onMessage(JSON.parse(frame.body)), { receipt });
      return () => {
        if (client.connected) {
          sub.unsubscribe();
        }
      };
    },
    send(destination, body) {
      if (client.connected) {
        client.publish({ destination, body: JSON.stringify(body) });
      }
    },
    onConnect(listener) {
      connectListeners.add(listener);
      return () => connectListeners.delete(listener);
    },
    onPageEnded(listener) {
      pageEndedListeners.add(listener);
      return () => pageEndedListeners.delete(listener);
    },
  };
}
