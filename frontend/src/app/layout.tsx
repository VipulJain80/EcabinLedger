import type { Metadata } from "next";
import "./globals.css";
import "./details.css";
import "./login.css";
import "./configuration.css";

export const metadata: Metadata = {
  title: "eCabin Ledger | Cabin Defect Operations",
  description: "Track cabin defects from inspection through closure with an audit-ready operational record.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="en"><body>{children}</body></html>;
}
