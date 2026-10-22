// The part of the STOMP connection that synchronisers use; tests replace it with a fake.
export interface RealtimeLink {
  connected(): boolean;
  // Subscribes with a receipt: `onReady` runs once the server has registered the subscription (INV-09).
  // Returns the unsubscribe function. Only call while connected.
  subscribe(destination: string, onMessage: (body: unknown) => void, onReady: () => void): () => void;
  send(destination: string, body: unknown): void;
  // Runs on every (re)connect; subscriptions of an earlier connection are gone by then.
  onConnect(listener: () => void): () => void;
  // Runs when the server refuses a frame because the page has ended.
  onPageEnded(listener: () => void): () => void;
}

export function chunkTopic(pageId: string, chunk: number): string {
  return `/topic/page.${pageId}.chunk.${chunk}`;
}

export function metaTopic(pageId: string): string {
  return `/topic/page.${pageId}.meta`;
}
