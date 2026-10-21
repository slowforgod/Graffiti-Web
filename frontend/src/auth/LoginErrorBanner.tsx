const MESSAGES: Record<string, string> = {
  DOMAIN_NOT_ALLOWED: '경희대학교 계정으로만 로그인할 수 있어요.',
  LOGIN_FAILED: '로그인하지 못했어요. 다시 시도해 주세요.',
};

interface Props {
  code: string | null;
  onClose: () => void;
}

export default function LoginErrorBanner({ code, onClose }: Props) {
  if (code === null) {
    return null;
  }
  return (
    <div role="alert">
      {MESSAGES[code] ?? MESSAGES.LOGIN_FAILED}{' '}
      <button type="button" onClick={onClose}>
        닫기
      </button>
    </div>
  );
}
