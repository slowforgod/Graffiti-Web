import { useState } from 'react';

interface Props {
  onAgree: () => Promise<void>;
  onCancel: () => void;
}

// Draft wording (SEC-13, D-38). Must be confirmed by the school privacy office before launch.
export default function ConsentScreen({ onAgree, onCancel }: Props) {
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);

  const agree = async () => {
    setBusy(true);
    setFailed(false);
    try {
      await onAgree();
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="consent-screen" role="dialog" aria-modal="true" aria-labelledby="consent-title">
      <h2 id="consent-title">개인정보 수집·이용 동의</h2>
      <dl>
        <dt>수집 항목</dt>
        <dd>학교 이메일 주소, 작성한 낙서</dd>
        <dt>이용 목적</dt>
        <dd>로그인 인증, 부적절한 낙서에 대한 제재, 부적절한 낙서 판별 모델 개선</dd>
        <dt>보관 기간</dt>
        <dd>서비스 운영 기간 동안 보관 (학교 확인 후 확정)</dd>
      </dl>
      <p>판별 모델 개선에는 작성자 정보를 지운 낙서 이미지만 사용해요. 동의해야 낙서를 남길 수 있어요.</p>
      {failed && <p role="alert">동의를 저장하지 못했어요. 다시 시도해 주세요.</p>}
      <button type="button" onClick={agree} disabled={busy}>
        동의하고 시작하기
      </button>{' '}
      <button type="button" onClick={onCancel} disabled={busy}>
        동의하지 않고 로그아웃
      </button>
    </section>
  );
}
