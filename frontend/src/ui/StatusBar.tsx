import type { PageStatus } from '../shared/pageConfig';
import { occupancyText, remainingText, startDateText } from './pageTime';

interface Props {
  seq: number;
  status: PageStatus;
  occupancyPermille: number;
  // When the page started (09 D2: always shown as "YY/MM/DD부터").
  createdAt: string;
  // Milliseconds until the page ends (D-15: the 7-day limit, or 6 hours after 70%).
  remainingMs: number;
  // The page's turn rules (D-15), for the hint before the countdown starts.
  thresholdPermille: number;
  countdownMs: number;
}

// D-16 (09 D2): occupancy and the start date are always shown at the top; the time left only once the
// countdown is on.
export default function StatusBar({ seq, status, occupancyPermille, createdAt, remainingMs, thresholdPermille, countdownMs }: Props) {
  const ending = remainingMs <= 0;
  const since = startDateText(createdAt);
  return (
    <div className="status-bar" role="group" aria-label="페이지 상태">
      <span>제{seq}장</span>
      <span>점유율 {occupancyText(occupancyPermille)}</span>
      {since && <span>{since}부터</span>}
      {ending ? (
        <span>페이지를 넘기는 중이에요…</span>
      ) : status === 'COUNTDOWN' ? (
        <span>{remainingText(remainingMs)} 남음</span>
      ) : (
        <span className="status-hint">
          {occupancyText(thresholdPermille).replace('.0%', '%')}가 차면 {remainingText(countdownMs)} 뒤 넘어가요
        </span>
      )}
    </div>
  );
}
