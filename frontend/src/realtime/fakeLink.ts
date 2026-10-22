import type { RealtimeLink } from './link';

interface FakeSubscription {
  destination: string;
  onMessage: (body: unknown) => void;
  onReady: () => void;
  active: boolean;
}

// A RealtimeLink for tests: subscriptions wait until `ready` and messages are delivered by hand.
export class FakeLink implements RealtimeLink {
  isConnected = false;
  readonly subscriptions: FakeSubscription[] = [];
  readonly sent: { destination: string; body: unknown }[] = [];
  private readonly connectListeners = new Set<() => void>();
  private readonly disconnectListeners = new Set<() => void>();
  private readonly pageEndedListeners = new Set<() => void>();

  connected(): boolean {
    return this.isConnected;
  }

  subscribe(destination: string, onMessage: (body: unknown) => void, onReady: () => void): () => void {
    const sub = { destination, onMessage, onReady, active: true };
    this.subscriptions.push(sub);
    return () => {
      sub.active = false;
    };
  }

  send(destination: string, body: unknown): void {
    this.sent.push({ destination, body });
  }

  onConnect(listener: () => void): () => void {
    this.connectListeners.add(listener);
    return () => this.connectListeners.delete(listener);
  }

  onDisconnect(listener: () => void): () => void {
    this.disconnectListeners.add(listener);
    return () => this.disconnectListeners.delete(listener);
  }

  onPageEnded(listener: () => void): () => void {
    this.pageEndedListeners.add(listener);
    return () => this.pageEndedListeners.delete(listener);
  }

  connect(): void {
    this.isConnected = true;
    this.subscriptions.forEach((s) => (s.active = false));
    this.connectListeners.forEach((l) => l());
  }

  disconnect(): void {
    this.isConnected = false;
    this.subscriptions.forEach((s) => (s.active = false));
    this.disconnectListeners.forEach((l) => l());
  }

  endPage(): void {
    this.pageEndedListeners.forEach((l) => l());
  }

  active(destination?: string): FakeSubscription[] {
    return this.subscriptions.filter((s) => s.active && (destination === undefined || s.destination === destination));
  }

  // Sends the receipts of every active subscription not yet ready.
  readyAll(): void {
    const pending = this.active().filter((s) => !(s as { ready?: boolean }).ready);
    pending.forEach((s) => {
      (s as { ready?: boolean }).ready = true;
      s.onReady();
    });
  }

  deliver(destination: string, body: unknown): void {
    this.active(destination).forEach((s) => s.onMessage(body));
  }
}
