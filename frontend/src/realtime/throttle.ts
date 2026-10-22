// Calls `fn` at most once per `intervalMs`; the latest value of a burst is sent at the end of the interval.
export function throttle<T>(intervalMs: number, fn: (value: T) => void, now: () => number = Date.now) {
  let last = -Infinity;
  let pending: { value: T } | null = null;
  let timer: ReturnType<typeof setTimeout> | null = null;

  const flush = () => {
    timer = null;
    if (pending) {
      last = now();
      const { value } = pending;
      pending = null;
      fn(value);
    }
  };

  return {
    push(value: T) {
      pending = { value };
      const wait = last + intervalMs - now();
      if (wait <= 0 && timer === null) {
        flush();
      } else if (timer === null) {
        timer = setTimeout(flush, wait);
      }
    },
    cancel() {
      if (timer !== null) {
        clearTimeout(timer);
        timer = null;
      }
      pending = null;
    },
  };
}
