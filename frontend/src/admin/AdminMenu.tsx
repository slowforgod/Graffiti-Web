import { useState } from 'react';
import type { Me } from '../auth/me';
import { ICONS } from '../ui/icons';
import AdminScreen from './AdminScreen';

interface Props {
  me: Me | undefined;
}

// The settings button at the top right, for admins only. It opens every admin tool in a drawer over the
// canvas, so the canvas keeps its place and connection.
export default function AdminMenu({ me }: Props) {
  const [open, setOpen] = useState(false);
  if (!(me?.authenticated && me.role === 'ADMIN')) {
    return null;
  }
  return (
    <>
      <button
        type="button"
        className="icon-button admin-menu-button"
        aria-label="관리자 설정"
        title="관리자 설정"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
      >
        <img src={ICONS.settings} alt="" />
      </button>
      {open && (
        <div className="admin-drawer" role="dialog" aria-label="관리자 설정">
          <AdminScreen me={me} onClose={() => setOpen(false)} />
        </div>
      )}
    </>
  );
}
