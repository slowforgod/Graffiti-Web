import { useCallback, useEffect, useState } from 'react';
import AuthBar from './auth/AuthBar';
import ConsentScreen from './auth/ConsentScreen';
import LoginErrorBanner from './auth/LoginErrorBanner';
import type { Me } from './auth/me';
import { consent, fetchMe, logout } from './auth/me';

const LOGIN_ERROR_PARAM = 'loginError';

// Reads ?loginError= once and removes it from the address bar so a reload does not show it again.
function takeLoginError(): string | null {
  const url = new URL(window.location.href);
  const code = url.searchParams.get(LOGIN_ERROR_PARAM);
  if (code !== null) {
    url.searchParams.delete(LOGIN_ERROR_PARAM);
    window.history.replaceState(null, '', url.pathname + url.search + url.hash);
  }
  return code;
}

// A failed lookup shows the signed-out bar; the user can still try to log in.
async function loadMe(): Promise<Me> {
  try {
    return await fetchMe();
  } catch {
    return { authenticated: false };
  }
}

export default function App() {
  const [me, setMe] = useState<Me>();
  const [loginError, setLoginError] = useState<string | null>(takeLoginError);

  const refresh = useCallback(async () => {
    setMe(await loadMe());
  }, []);

  useEffect(() => {
    let active = true;
    void loadMe().then((loaded) => {
      if (active) {
        setMe(loaded);
      }
    });
    return () => {
      active = false;
    };
  }, []);

  const signOut = useCallback(async () => {
    try {
      await logout();
    } finally {
      await refresh();
    }
  }, [refresh]);

  const agree = useCallback(async () => {
    setMe(await consent());
  }, []);

  return (
    <main>
      <h1>경희 낙서장</h1>
      <LoginErrorBanner code={loginError} onClose={() => setLoginError(null)} />
      <AuthBar me={me} onLogout={() => void signOut()} />
      {me?.authenticated && me.consentRequired && (
        <ConsentScreen onAgree={agree} onCancel={() => void signOut()} />
      )}
    </main>
  );
}
