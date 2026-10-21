import { render, screen } from '@testing-library/react';
import App from './App';

describe('App', () => {
  it('renders the Korean service title', () => {
    render(<App />);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('경희 낙서장');
  });
});
