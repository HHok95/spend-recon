import type { Metadata } from "next";
import { Instrument_Sans, JetBrains_Mono } from "next/font/google";
import { Landing } from "@/components/landing/view";
import "./landing.css";

const sans = Instrument_Sans({
  subsets: ["latin"],
  display: "optional",
  variable: "--font-landing-sans",
});

const mono = JetBrains_Mono({
  subsets: ["latin"],
  weight: ["400", "500", "600", "700", "800"],
  display: "optional",
  variable: "--font-landing-mono",
});

export const metadata: Metadata = {
  description:
    "Each card charge is scored against its receipts. Strong matches clear on their own, the rest wait for a person, and every change is on the record.",
};

export default function Page() {
  return (
    <>
      <link rel="preload" as="image" href="/landing/hero.webp" media="(min-width: 768px)" type="image/webp" />
      <Landing fontClass={`${sans.variable} ${mono.variable}`} />
    </>
  );
}
