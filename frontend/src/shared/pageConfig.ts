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
  // Any whole stroke width in this range, for the pen and the eraser (D-07).
  strokeWidthMin: number;
  strokeWidthMax: number;
  defaultStrokeWidth: number;
  fontSizes: number[];
  defaultFontSize: number;
  // Font ids in picker order and the one picked first (13); fonts/fonts.ts knows their files.
  fonts: string[];
  defaultFont: string;
  // How wide a letter may be, in percent of the font size (D-31).
  textWidthPercent: number;
  textMaxLength: number;
  maxBbox: number;
  maxChunks: number;
  postCooldownSec: number;
  countdownWarningSec: number;
  draftIdleTimeoutSec: number;
  cursorIntervalMs: number;
  draftIntervalMs: number;
  heartbeatMs: number;
  // The page's turn rules (D-15), from its row: the countdown starts at this occupancy and lasts this long.
  countdownThresholdPermille: number;
  countdownDurationSec: number;
}

export type PageStatus = 'ACTIVE' | 'COUNTDOWN' | 'ENDED';

export interface CurrentPage {
  id: string;
  seq: number;
  status: PageStatus;
  occupancyPermille: number;
  // When the page started; the status bar shows it as "YY/MM/DD부터" (09 D2).
  createdAt: string;
  countdownStartedAt: string | null;
  scheduledEndAt: string;
  serverTime: string;
  config: ClientConfig;
}

export function fetchCurrentPage(): Promise<CurrentPage> {
  return apiFetch<CurrentPage>('/api/pages/current');
}
