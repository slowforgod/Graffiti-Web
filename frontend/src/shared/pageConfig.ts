import { apiFetch } from '../api/client';

// Every canvas number the client uses comes from here (INV-10). Sizes are the page's snapshot (D-06).
export interface ClientConfig {
  width: number;
  height: number;
  chunkSize: number;
  cellSize: number;
  zoomMin: number;
  zoomMax: number;
  palette: string[];
  strokeWidths: number[];
  fontSizes: number[];
  defaultFontSize: number;
  textMaxLength: number;
  maxBbox: number;
  maxChunks: number;
  postCooldownSec: number;
  countdownWarningSec: number;
  draftIdleTimeoutSec: number;
}

export type PageStatus = 'ACTIVE' | 'COUNTDOWN' | 'ENDED';

export interface CurrentPage {
  id: string;
  seq: number;
  status: PageStatus;
  occupancyPermille: number;
  countdownStartedAt: string | null;
  scheduledEndAt: string;
  serverTime: string;
  config: ClientConfig;
}

export function fetchCurrentPage(): Promise<CurrentPage> {
  return apiFetch<CurrentPage>('/api/pages/current');
}
