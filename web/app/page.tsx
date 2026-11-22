import { Demo } from "@/components/demo";
import { StartLink } from "@/components/start-link";

export default function Home() {
  return (
    <main className="page">
      <header className="hero">
        <p className="kicker">spend-recon</p>
        <h1>A backend that turns card spend into matched, approved, auditable records, safely.</h1>
        <div className="hero-actions">
          <StartLink />
          <nav aria-label="Elsewhere">
            <a href="https://github.com/HHok95/spend-recon" target="_blank" rel="noopener noreferrer">
              GitHub
            </a>
            <a href="https://hokheng.com" target="_blank" rel="noopener noreferrer">
              hokheng.com
            </a>
          </nav>
        </div>
      </header>
      <Demo />
    </main>
  );
}
