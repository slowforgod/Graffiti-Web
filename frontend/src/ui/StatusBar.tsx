import type { PageStatus } from '../shared/pageConfig';
import { occupancyText, remainingText } from './pageTime';

interface Props {
  seq: number;
  status: PageStatus;
  occupancyPermille: number;
  // Milliseconds until the page ends (D-15: the 7-day limit, or 6 hours after 70%).
  remainingMs: number;
  // The page's turn rules (D-15), for the hint before the countdown starts.
  thresholdPermille: number;
  countdownMs: number;
}

// D-16: occupancy and the time left are always shown at the top.
export default function StatusBar({ seq, status, occupancyPermille, remainingMs, thresholdPermille, countdownMs }: Props) {
  const ending = remainingMs <= 0;
  return (
    <div className="status-bar" role="group" aria-label="페이지 상태">
      <span>제{seq}장</span>
      <span>점유율 {occupancyText(occupancyPermille)}</span>
      <span>
        {ending
          ? '페이지를 넘기는 중이에요…'
          : status === 'COUNTDOWN'
            ? `${remainingText(remainingMs)} 뒤 넘어가요`
            : `남은 시간 ${remainingText(remainingMs)}`}
      </span>
      {status === 'ACTIVE' && !ending && (
        <span className="status-hint">
          {occupancyText(thresholdPermille).replace('.0%', '%')}가 차면 {remainingText(countdownMs)} 뒤 넘어가요
        </span>
      )}
    </div>
  );
}
