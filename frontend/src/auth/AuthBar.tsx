import type { Me } from './me';
import { LOGIN_URL } from './me';

interface Props {
  me: Me | undefined;
  onLogout: () => void;
}

// Temporary top bar until the toolbar lands in 05-draft.
export default function AuthBar({ me, onLogout }: Props) {
  if (me === undefined) {
    return null;
  }
  if (!me.authenticated) {
    return (
      <nav aria-label="계정">
        <a href={LOGIN_URL}>경희대 계정으로 로그인</a>
      </nav>
    );
  }
  return (
    <nav aria-label="계정">
      <span style={{ color: me.cursorColor }}>●</span> <span>{me.nickname}</span>{' '}
      <button type="button" onClick={onLogout}>
        로그아웃
      </button>
    </nav>
  );
}
