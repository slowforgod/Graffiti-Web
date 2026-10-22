import { useSyncExternalStore } from 'react';
import type { Size } from './viewport';

function subscribe(onChange: () => void): () => void {
  window.addEventListener('resize', onChange);
  return () => window.removeEventListener('resize', onChange);
}

export function useWindowSize(): Size {
  const width = useSyncExternalStore(subscribe, () => window.innerWidth);
  const height = useSyncExternalStore(subscribe, () => window.innerHeight);
  return { width, height };
}
