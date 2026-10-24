// Page end times (D-15, D-16) on the server's clock: the client keeps the difference between its clock and
// the server's from the page load (`serverTime`, see tools/cooldown.ts).

const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

// Milliseconds until the page ends; 0 once it is due.
export function untilEnd(scheduledEndAt: string, offsetMs: number, now: number = Date.now()): number {
  const end = Date.parse(scheduledEndAt);
  return Number.isNaN(end) ? 0 : Math.max(0, end - (now + offsetMs));
}

// The status bar during the countdown and the turn hint: whole days and hours, hours and minutes, or minutes, rounded up so a page
// never reads "0분" before it ends.
export function remainingText(ms: number): string {
  const minutes = Math.max(1, Math.ceil(ms / MINUTE));
  if (minutes >= DAY / MINUTE) {
    const hours = Math.ceil(ms / HOUR);
    const days = Math.floor(hours / 24);
    const rest = hours % 24;
    return rest === 0 ? `${days}일` : `${days}일 ${rest}시간`;
  }
  if (minutes >= 60) {
    const hours = Math.floor(minutes / 60);
    const rest = minutes % 60;
    return rest === 0 ? `${hours}시간` : `${hours}시간 ${rest}분`;
  }
  return `${minutes}분`;
}

// The page's start date as the status bar shows it: "26/10/24", in the viewer's local time.
export function startDateText(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) {
    return '';
  }
  const two = (n: number) => String(n).padStart(2, '0');
  return `${two(d.getFullYear() % 100)}/${two(d.getMonth() + 1)}/${two(d.getDate())}`;
}

// The big T−5 countdown: "4:59", rounded up to whole seconds.
export function clockText(ms: number): string {
  const seconds = Math.ceil(ms / SECOND);
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
}

// Occupancy as the server counts it (per mille), shown with one decimal: 712 → "71.2%".
export function occupancyText(permille: number): string {
  return `${(permille / 10).toFixed(1)}%`;
}

// When to look at the clock again: every second near and during the warning, else every half minute.
export function nextTick(ms: number, warningMs: number): number {
  if (ms <= warningMs + MINUTE) {
    return SECOND;
  }
  return 30 * SECOND;
}
