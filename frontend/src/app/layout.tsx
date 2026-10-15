import type { Metadata } from "next";
import localFont from "next/font/local";
import "./globals.css";
import "./details.css";
import "./login.css";
import "./configuration.css";
import "./tailwind.css";
import "./responsive.css";
import "sweetalert2/dist/sweetalert2.min.css";
import QueryProvider from "@/common/query/QueryProvider";

const montserrat = localFont({
  src: [
    { path: "../../public/fonts/montserrat-400.ttf", weight: "400", style: "normal" },
    { path: "../../public/fonts/montserrat-500.ttf", weight: "500", style: "normal" },
    { path: "../../public/fonts/montserrat-600.ttf", weight: "600", style: "normal" },
    { path: "../../public/fonts/montserrat-700.ttf", weight: "700", style: "normal" },
  ],
  variable: "--font-montserrat",
  display: "swap",
});

export const metadata: Metadata = {
  title: "Defect Summary | eCabin Ledger",
  description: "Track cabin defects from inspection through closure with an audit-ready operational record.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="en" className={montserrat.variable}><body><QueryProvider>{children}</QueryProvider></body></html>;
}
