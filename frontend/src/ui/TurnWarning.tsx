import { clockText } from './pageTime';

interface Props {
  remainingMs: number;
}

// D-16: from T−5 minutes, a big countdown at the top left with the warning about drafts.
export default function TurnWarning({ remainingMs }: Props) {
  return (
    <div className="turn-warning" role="timer" aria-label="페이지 넘김까지 남은 시간">
      <strong>{clockText(remainingMs)}</strong>
      <p>페이지가 넘어가면 그리고 있는 초안도 사라져요</p>
    </div>
  );
}
